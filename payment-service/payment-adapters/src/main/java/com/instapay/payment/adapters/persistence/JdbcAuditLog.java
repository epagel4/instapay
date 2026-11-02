package com.instapay.payment.adapters.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instapay.payment.application.port.out.AuditLog;
import com.instapay.payment.domain.shared.DomainEvent;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAuditLog implements AuditLog {

  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public JdbcAuditLog(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Override
  public void append(DomainEvent event) {
    try {
      jdbc.update(
          "INSERT INTO audit_event (aggregate_type, aggregate_id, event_type, payload, occurred_at) VALUES (?, ?, ?, ?::jsonb, ?)",
          event.aggregateType(),
          event.aggregateId(),
          event.eventType(),
          json.writeValueAsString(event),
          Timestamp.from(event.occurredAt()));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot serialize " + event.eventType(), e);
    }
  }
}
