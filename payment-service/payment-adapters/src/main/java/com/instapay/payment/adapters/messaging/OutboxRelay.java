package com.instapay.payment.adapters.messaging;

import io.micrometer.tracing.Span;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Polls unpublished outbox rows and sends them to Kafka (ADR-006). At-least-once: a crash after
 * sending but before marking re-sends the batch, so consumers must be idempotent. SKIP LOCKED lets
 * several instances relay in parallel without sending the same row twice concurrently.
 */
@Component
class OutboxRelay {

  private static final int BATCH = 100;

  record Row(UUID id, String topic, String key, String type, String payload, String traceParent) {}

  private final JdbcTemplate jdbc;
  private final KafkaTemplate<String, String> kafka;
  private final TransactionTemplate tx;
  private final OutboxTracing tracing;

  OutboxRelay(
      JdbcTemplate jdbc,
      KafkaTemplate<String, String> kafka,
      TransactionTemplate tx,
      OutboxTracing tracing) {
    this.tracing = tracing;
    this.jdbc = jdbc;
    this.kafka = kafka;
    this.tx = tx;
  }

  @Scheduled(fixedDelayString = "${instapay.outbox.poll-interval-ms}")
  void publishPending() {
    tx.executeWithoutResult(
        status -> {
          List<Row> rows =
              jdbc.query(
                  """
              SELECT id, topic, message_key, event_type, payload, trace_parent FROM outbox_event
              WHERE published_at IS NULL ORDER BY created_at LIMIT ? FOR UPDATE SKIP LOCKED
              """,
                  (rs, i) ->
                      new Row(
                          rs.getObject("id", UUID.class),
                          rs.getString("topic"),
                          rs.getString("message_key"),
                          rs.getString("event_type"),
                          rs.getString("payload"),
                          rs.getString("trace_parent")),
                  BATCH);
          if (rows.isEmpty()) {
            return;
          }
          List<CompletableFuture<SendResult<String, String>>> sends = new ArrayList<>();
          for (Row row : rows) {
            ProducerRecord<String, String> record =
                new ProducerRecord<>(row.topic(), row.key(), row.payload());
            record.headers().add("messageId", row.id().toString().getBytes(StandardCharsets.UTF_8));
            record.headers().add("eventType", row.type().getBytes(StandardCharsets.UTF_8));
            Span span = tracing.startPublish(row.traceParent(), row.topic(), record.headers());
            sends.add(kafka.send(record).whenComplete((r, e) -> end(span, e)));
          }
          for (CompletableFuture<SendResult<String, String>> send : sends) {
            try {
              send.get(
                  10,
                  TimeUnit.SECONDS); // any failure rolls back: rows stay pending, retried next poll
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException(e);
            } catch (Exception e) {
              throw new IllegalStateException("Outbox publish failed", e);
            }
          }
          jdbc.batchUpdate(
              "UPDATE outbox_event SET published_at = now() WHERE id = ?",
              rows.stream().map(r -> new Object[] {r.id()}).toList());
        });
  }

  private static void end(Span span, Throwable error) {
    if (error != null) {
      span.error(error);
    }
    span.end();
  }
}
