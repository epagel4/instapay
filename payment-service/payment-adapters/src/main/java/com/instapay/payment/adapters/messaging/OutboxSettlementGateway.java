package com.instapay.payment.adapters.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instapay.contracts.SettlementRequest;
import com.instapay.contracts.Topics;
import com.instapay.payment.application.port.out.SettlementGateway;
import com.instapay.payment.domain.transfer.Transfer;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Writes the request to the outbox in the caller's transaction; OutboxRelay publishes it. */
@Component
class OutboxSettlementGateway implements SettlementGateway {

  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final OutboxTracing tracing;

  OutboxSettlementGateway(JdbcTemplate jdbc, ObjectMapper json, OutboxTracing tracing) {
    this.jdbc = jdbc;
    this.json = json;
    this.tracing = tracing;
  }

  @Override
  public void requestSettlement(Transfer t, Instant expiresAt) {
    SettlementRequest request =
        new SettlementRequest(
            t.id().value(),
            t.debtor().institution().value(),
            t.debtor().account().value(),
            t.creditor().institution().value(),
            t.creditor().account().value(),
            t.amount().cents(),
            t.createdAt(),
            expiresAt);
    try {
      jdbc.update(
          """
          INSERT INTO outbox_event (id, topic, message_key, event_type, payload, trace_parent)
          VALUES (?, ?, ?, ?, ?, ?)
          """,
          UUID.randomUUID(),
          Topics.SETTLEMENT_REQUESTS,
          t.id().toString(),
          "SettlementRequest",
          json.writeValueAsString(request),
          tracing.currentTraceParent());
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
