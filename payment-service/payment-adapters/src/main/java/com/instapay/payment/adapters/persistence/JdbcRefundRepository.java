package com.instapay.payment.adapters.persistence;

import com.instapay.payment.application.port.out.RefundRepository;
import com.instapay.payment.domain.shared.Money;
import com.instapay.payment.domain.transfer.Refund;
import com.instapay.payment.domain.transfer.RefundId;
import com.instapay.payment.domain.transfer.TransferId;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRefundRepository implements RefundRepository {

  private final JdbcTemplate jdbc;

  public JdbcRefundRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(Refund r) {
    jdbc.update(
        "INSERT INTO refund (id, original_transfer_id, amount_cents, reason, created_at) VALUES (?, ?, ?, ?, ?)",
        r.id().value(),
        r.originalTransfer().value(),
        r.amount().cents(),
        r.reason(),
        Timestamp.from(r.createdAt()));
  }

  @Override
  public List<Refund> listByTransfer(TransferId transfer) {
    return jdbc.query(
        "SELECT * FROM refund WHERE original_transfer_id = ? ORDER BY created_at, id",
        (rs, i) ->
            new Refund(
                new RefundId(rs.getObject("id", UUID.class)),
                new TransferId(rs.getObject("original_transfer_id", UUID.class)),
                Money.ofCents(rs.getLong("amount_cents")),
                rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant()),
        transfer.value());
  }

  @Override
  public Money totalFor(TransferId transfer) {
    Long cents =
        jdbc.queryForObject(
            "SELECT coalesce(sum(amount_cents), 0)::bigint FROM refund WHERE original_transfer_id = ?",
            Long.class,
            transfer.value());
    return Money.ofCents(cents == null ? 0 : cents);
  }
}
