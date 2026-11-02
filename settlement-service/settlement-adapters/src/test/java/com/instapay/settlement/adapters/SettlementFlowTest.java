package com.instapay.settlement.adapters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instapay.contracts.SettlementRequest;
import com.instapay.contracts.SettlementResult;
import com.instapay.contracts.Topics;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

/** Plays the payment-service: publishes requests, collects the replies. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.otlp.tracing.export.enabled=false")
@AutoConfigureObservability(metrics = false) // tracing on: the reply must continue the trace
class SettlementFlowTest extends IntegrationTest {

  static final Map<UUID, List<SettlementResult>> RESULTS = new ConcurrentHashMap<>();
  static final Map<UUID, String> TRACEPARENTS = new ConcurrentHashMap<>();
  static volatile boolean running = true;
  static Thread collector;

  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired TestRestTemplate http;
  @Autowired Tracer tracer;

  @BeforeAll
  static void collectResults() {
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-payment-" + UUID.randomUUID());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.METADATA_MAX_AGE_CONFIG, "500");
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    collector =
        Thread.ofVirtual()
            .start(
                () -> {
                  try (var consumer =
                      new KafkaConsumer<>(
                          props, new StringDeserializer(), new StringDeserializer())) {
                    consumer.subscribe(List.of(Topics.SETTLEMENT_RESULTS));
                    while (running) {
                      for (var record : consumer.poll(Duration.ofMillis(100))) {
                        try {
                          SettlementResult r =
                              mapper.readValue(record.value(), SettlementResult.class);
                          Header tp = record.headers().lastHeader("traceparent");
                          if (tp != null) {
                            TRACEPARENTS.put(
                                r.transferId(), new String(tp.value(), StandardCharsets.UTF_8));
                          }
                          RESULTS
                              .computeIfAbsent(r.transferId(), k -> new CopyOnWriteArrayList<>())
                              .add(r);
                        } catch (Exception e) {
                          throw new IllegalStateException(e);
                        }
                      }
                    }
                  }
                });
  }

  @AfterAll
  static void stop() throws InterruptedException {
    running = false;
    collector.join(5000);
  }

  SettlementRequest request(String creditorIspb, Instant expiresAt) {
    return new SettlementRequest(
        UUID.randomUUID(),
        "00000001",
        UUID.randomUUID(),
        creditorIspb,
        UUID.randomUUID(),
        2500,
        Instant.now(),
        expiresAt);
  }

  void publish(SettlementRequest r) throws Exception {
    kafka
        .send(Topics.SETTLEMENT_REQUESTS, r.transferId().toString(), json.writeValueAsString(r))
        .get();
  }

  List<SettlementResult> awaitResults(UUID transferId, int count) {
    await()
        .atMost(Duration.ofSeconds(15))
        .until(() -> RESULTS.getOrDefault(transferId, List.of()).size() >= count);
    return RESULTS.get(transferId);
  }

  @Test
  void settlesValidRequestAndExposesDecision() throws Exception {
    SettlementRequest r = request("00000002", Instant.now().plusSeconds(30));
    publish(r);

    SettlementResult result = awaitResults(r.transferId(), 1).getFirst();

    assertThat(result.status()).isEqualTo(SettlementResult.Status.SETTLED);
    assertThat(result.reason()).isNull();
    var api = http.getForEntity("/api/v1/settlements/" + r.transferId(), String.class);
    assertThat(api.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(api.getBody()).contains("SETTLED").contains("\"amountCents\":2500");
  }

  @Test
  void duplicateRequestRepublishesTheSameDecisionWithoutDecidingAgain() throws Exception {
    SettlementRequest r = request("00000003", Instant.now().plusSeconds(30));
    publish(r);
    SettlementResult first = awaitResults(r.transferId(), 1).getFirst();

    publish(r);

    List<SettlementResult> results = awaitResults(r.transferId(), 2);
    assertThat(results.get(1)).isEqualTo(first); // same decidedAt: not re-decided
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM settlement WHERE transfer_id = ?",
                Long.class,
                r.transferId()))
        .isEqualTo(1);
  }

  @Test
  void expiredRequestIsRejectedSoTheSenderCanCompensateSafely() throws Exception {
    SettlementRequest r = request("00000002", Instant.now().minusSeconds(1));
    publish(r);

    SettlementResult result = awaitResults(r.transferId(), 1).getFirst();

    assertThat(result.status()).isEqualTo(SettlementResult.Status.REJECTED);
    assertThat(result.reason()).isEqualTo("expired");
  }

  @Test
  void unknownInstitutionIsRejected() throws Exception {
    SettlementRequest r = request("99999999", Instant.now().plusSeconds(30));
    publish(r);

    SettlementResult result = awaitResults(r.transferId(), 1).getFirst();

    assertThat(result.status()).isEqualTo(SettlementResult.Status.REJECTED);
    assertThat(result.reason()).isEqualTo("unknown_creditor_institution");
    assertThat(
            http.getForEntity("/api/v1/settlements/" + UUID.randomUUID(), String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void replyContinuesTheTraceOfTheRequest() throws Exception {
    SettlementRequest r = request("00000002", Instant.now().plusSeconds(30));
    Span caller = tracer.nextSpan().name("payment-service outbox").start();
    try (Tracer.SpanInScope ignored = tracer.withSpan(caller)) {
      publish(r);
    } finally {
      caller.end();
    }

    awaitResults(r.transferId(), 1);

    await().atMost(Duration.ofSeconds(5)).until(() -> TRACEPARENTS.containsKey(r.transferId()));
    assertThat(TRACEPARENTS.get(r.transferId()).split("-")[1])
        .isEqualTo(caller.context().traceId());
  }
}
