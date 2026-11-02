package com.instapay.payment.adapters.persistence;

import com.instapay.payment.application.port.out.TransferRepository;
import com.instapay.payment.domain.account.AccountId;
import com.instapay.payment.domain.account.AccountRef;
import com.instapay.payment.domain.shared.Ispb;
import com.instapay.payment.domain.shared.Money;
import com.instapay.payment.domain.transfer.Transfer;
import com.instapay.payment.domain.transfer.TransferId;
import com.instapay.payment.domain.transfer.TransferStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTransferRepository implements TransferRepository {

  private final JdbcTemplate jdbc;

  public JdbcTransferRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(Transfer t) {
    jdbc.update(
        """
        INSERT INTO transfer (id, debtor_institution, debtor_account, creditor_institution,
                              creditor_account, amount_cents, status, failure_reason, created_at, finished_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        t.id().value(),
        t.debtor().institution().value(),
        t.debtor().account().value(),
        t.creditor().institution().value(),
        t.creditor().account().value(),
        t.amount().cents(),
        t.status().name(),
        t.failureReason().orElse(null),
        Timestamp.from(t.createdAt()),
        t.finishedAt().map(Timestamp::from).orElse(null));
  }

  @Override
  public void update(Transfer t) {
    jdbc.update(
        "UPDATE transfer SET status = ?, failure_reason = ?, finished_at = ? WHERE id = ?",
        t.status().name(),
        t.failureReason().orElse(null),
        t.finishedAt().map(Timestamp::from).orElse(null),
        t.id().value());
  }

  @Override
  public Optional<Transfer> findById(TransferId id) {
    return find("SELECT * FROM transfer WHERE id = ?", id);
  }

  @Override
  public Optional<Transfer> findByIdForUpdate(TransferId id) {
    return find("SELECT * FROM transfer WHERE id = ? FOR UPDATE", id);
  }

  @Override
  public Money sumOutgoing(AccountId debtor, Instant from, Instant to) {
    Long cents =
        jdbc.queryForObject(
            """
            SELECT coalesce(sum(amount_cents), 0)::bigint FROM transfer
            WHERE debtor_account = ? AND created_at >= ? AND created_at < ? AND status <> 'FAILED'
            """,
            Long.class,
            debtor.value(),
            Timestamp.from(from),
            Timestamp.from(to));
    return Money.ofCents(cents == null ? 0 : cents);
  }

  private Optional<Transfer> find(String sql, TransferId id) {
    return jdbc
        .query(
            sql,
            (rs, i) ->
                Transfer.restore(
                    new TransferId(rs.getObject("id", UUID.class)),
                    new AccountRef(
                        new Ispb(rs.getString("debtor_institution")),
                        new AccountId(rs.getObject("debtor_account", UUID.class))),
                    new AccountRef(
                        new Ispb(rs.getString("creditor_institution")),
                        new AccountId(rs.getObject("creditor_account", UUID.class))),
                    Money.ofCents(rs.getLong("amount_cents")),
                    rs.getTimestamp("created_at").toInstant(),
                    TransferStatus.valueOf(rs.getString("status")),
                    toInstant(rs.getTimestamp("finished_at")),
                    rs.getString("failure_reason")),
            id.value())
        .stream()
        .findFirst();
  }

  private static Instant toInstant(Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }
}
