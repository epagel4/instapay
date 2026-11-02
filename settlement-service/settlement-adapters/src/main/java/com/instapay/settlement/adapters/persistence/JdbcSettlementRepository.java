package com.instapay.settlement.adapters.persistence;

import com.instapay.settlement.application.port.out.SettlementRepository;
import com.instapay.settlement.domain.Settlement;
import com.instapay.settlement.domain.SettlementStatus;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSettlementRepository implements SettlementRepository {

  private static final RowMapper<Settlement> MAPPER =
      (rs, i) ->
          new Settlement(
              rs.getObject("transfer_id", UUID.class),
              rs.getString("debtor_ispb"),
              rs.getString("creditor_ispb"),
              rs.getObject("creditor_account", UUID.class),
              rs.getLong("amount_cents"),
              SettlementStatus.valueOf(rs.getString("status")),
              rs.getString("reason"),
              rs.getTimestamp("decided_at").toInstant());

  private final JdbcTemplate jdbc;

  public JdbcSettlementRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Settlement> findByTransferId(UUID transferId) {
    return jdbc.query("SELECT * FROM settlement WHERE transfer_id = ?", MAPPER, transferId).stream()
        .findFirst();
  }

  @Override
  public Settlement insertIfAbsent(Settlement s) {
    jdbc.update(
        """
        INSERT INTO settlement (transfer_id, debtor_ispb, creditor_ispb, creditor_account, amount_cents,
                                status, reason, decided_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (transfer_id) DO NOTHING
        """,
        s.transferId(),
        s.debtorIspb(),
        s.creditorIspb(),
        s.creditorAccount(),
        s.amountCents(),
        s.status().name(),
        s.reason(),
        Timestamp.from(s.decidedAt()));
    return findByTransferId(s.transferId()).orElseThrow();
  }
}
