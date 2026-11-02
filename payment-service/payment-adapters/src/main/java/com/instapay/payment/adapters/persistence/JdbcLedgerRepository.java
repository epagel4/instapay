package com.instapay.payment.adapters.persistence;

import com.instapay.payment.application.port.out.LedgerRepository;
import com.instapay.payment.domain.account.AccountId;
import com.instapay.payment.domain.ledger.JournalEntry;
import com.instapay.payment.domain.ledger.Posting;
import com.instapay.payment.domain.shared.Money;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcLedgerRepository implements LedgerRepository {

  private final JdbcTemplate jdbc;

  public JdbcLedgerRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional
  public void append(JournalEntry entry) {
    jdbc.update(
        "INSERT INTO journal_entry (id, type, reference, occurred_at) VALUES (?, ?, ?, ?)",
        entry.id().value(),
        entry.type().name(),
        entry.reference(),
        Timestamp.from(entry.occurredAt()));
    List<Object[]> rows = entry.postings().stream().map(p -> row(entry, p)).toList();
    jdbc.batchUpdate(
        "INSERT INTO posting (journal_entry_id, account_id, amount_cents) VALUES (?, ?, ?)", rows);
  }

  @Override
  public Money balanceOf(AccountId account) {
    Long cents =
        jdbc.queryForObject(
            "SELECT coalesce(sum(amount_cents), 0)::bigint FROM posting WHERE account_id = ?",
            Long.class,
            account.value());
    return Money.ofCents(cents == null ? 0 : cents);
  }

  private static Object[] row(JournalEntry entry, Posting posting) {
    return new Object[] {entry.id().value(), posting.account().value(), posting.amount().cents()};
  }
}
