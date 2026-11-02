package com.instapay.payment.adapters.persistence;

import com.instapay.payment.application.port.out.SettlementSagaRepository;
import com.instapay.payment.domain.transfer.SagaState;
import com.instapay.payment.domain.transfer.SettlementSaga;
import com.instapay.payment.domain.transfer.TransferId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSettlementSagaRepository implements SettlementSagaRepository {

  private static final RowMapper<SettlementSaga> MAPPER =
      (rs, i) -> {
        Timestamp finishedAt = rs.getTimestamp("finished_at");
        return SettlementSaga.restore(
            new TransferId(rs.getObject("transfer_id", UUID.class)),
            rs.getTimestamp("started_at").toInstant(),
            rs.getTimestamp("settlement_expires_at").toInstant(),
            rs.getTimestamp("compensate_after").toInstant(),
            SagaState.valueOf(rs.getString("state")),
            rs.getString("reason"),
            finishedAt == null ? null : finishedAt.toInstant());
      };

  private final JdbcTemplate jdbc;

  public JdbcSettlementSagaRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(SettlementSaga s) {
    jdbc.update(
        """
        INSERT INTO settlement_saga (transfer_id, state, started_at, settlement_expires_at, compensate_after)
        VALUES (?, ?, ?, ?, ?)
        """,
        s.transferId().value(),
        s.state().name(),
        Timestamp.from(s.startedAt()),
        Timestamp.from(s.settlementExpiresAt()),
        Timestamp.from(s.compensateAfter()));
  }

  @Override
  public Optional<SettlementSaga> findByIdForUpdate(TransferId id) {
    return jdbc
        .query("SELECT * FROM settlement_saga WHERE transfer_id = ? FOR UPDATE", MAPPER, id.value())
        .stream()
        .findFirst();
  }

  @Override
  public List<SettlementSaga> lockOverdue(Instant now, int limit) {
    return jdbc.query(
        """
        SELECT * FROM settlement_saga
        WHERE state = 'AWAITING_SETTLEMENT' AND compensate_after <= ?
        ORDER BY compensate_after
        LIMIT ?
        FOR UPDATE SKIP LOCKED
        """,
        MAPPER,
        Timestamp.from(now),
        limit);
  }

  @Override
  public void update(SettlementSaga s) {
    jdbc.update(
        "UPDATE settlement_saga SET state = ?, reason = ?, finished_at = ? WHERE transfer_id = ?",
        s.state().name(),
        s.reason().orElse(null),
        s.finishedAt().map(Timestamp::from).orElse(null),
        s.transferId().value());
  }
}
