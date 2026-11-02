package com.instapay.payment.adapters.persistence;

import static com.instapay.payment.adapters.persistence.LedgerDatabase.JDBC;
import static com.instapay.payment.adapters.persistence.LedgerDatabase.REPOSITORY;
import static com.instapay.payment.adapters.persistence.LedgerDatabase.TX;
import static com.instapay.payment.adapters.persistence.LedgerDatabase.entryExists;
import static com.instapay.payment.adapters.persistence.LedgerDatabase.insertRawEntry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.instapay.payment.domain.account.AccountId;
import com.instapay.payment.domain.ledger.JournalEntry;
import com.instapay.payment.domain.ledger.JournalEntryId;
import com.instapay.payment.domain.shared.Money;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

class LedgerDatabaseConstraintsTest {

  static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");

  @Test
  void appendsEntriesAndDerivesBalances() {
    AccountId alice = AccountId.newId(), bob = AccountId.newId();

    TX.executeWithoutResult(
        s -> {
          REPOSITORY.append(
              JournalEntry.deposit(JournalEntryId.newId(), alice, Money.of("100.00"), NOW));
          REPOSITORY.append(
              JournalEntry.transfer(
                  JournalEntryId.newId(), UUID.randomUUID(), alice, bob, Money.of("30.25"), NOW));
        });

    assertThat(REPOSITORY.balanceOf(alice)).isEqualTo(Money.of("69.75"));
    assertThat(REPOSITORY.balanceOf(bob)).isEqualTo(Money.of("30.25"));
    assertThat(REPOSITORY.balanceOf(AccountId.newId())).isEqualTo(Money.ZERO);
  }

  @Test
  void appendOutsideTransactionPersistsNothing() {
    // Outside Spring the adapter's @Transactional is inert: each statement autocommits, and the
    // deferred trigger on journal_entry rejects the header alone (zero postings), so no orphan.
    JournalEntry entry =
        JournalEntry.deposit(JournalEntryId.newId(), AccountId.newId(), Money.of("1"), NOW);

    assertThatThrownBy(() -> REPOSITORY.append(entry)).hasStackTraceContaining("unbalanced");
    assertThat(entryExists(entry.id().value())).isFalse();
  }

  @Test
  void rejectsUnbalancedEntryAtCommit() {
    UUID id = UUID.randomUUID();

    // Detected at COMMIT, so Spring reports it as TransactionSystemException.
    assertThatThrownBy(() -> insertRawEntry(id, 10_000, -9_999))
        .hasStackTraceContaining("unbalanced");
    assertThat(entryExists(id)).isFalse();
  }

  @Test
  void rejectsEntryWithSinglePostingOrNone() {
    UUID single = UUID.randomUUID(), none = UUID.randomUUID();

    assertThatThrownBy(() -> insertRawEntry(single, 500)).hasStackTraceContaining("unbalanced");
    assertThatThrownBy(() -> insertRawEntry(none)).hasStackTraceContaining("unbalanced");
    assertThat(entryExists(single)).isFalse();
    assertThat(entryExists(none)).isFalse();
  }

  @Test
  void rejectsZeroAmountPosting() {
    assertThatThrownBy(() -> insertRawEntry(UUID.randomUUID(), 0, 0))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("amount_cents");
  }

  @Test
  void ledgerIsAppendOnly() {
    UUID id = UUID.randomUUID();
    insertRawEntry(id, 700, -700);

    assertThatThrownBy(
            () -> JDBC.update("UPDATE posting SET amount_cents = 1 WHERE journal_entry_id = ?", id))
        .hasStackTraceContaining("append-only");
    assertThatThrownBy(() -> JDBC.update("DELETE FROM posting WHERE journal_entry_id = ?", id))
        .hasStackTraceContaining("append-only");
    assertThatThrownBy(
            () -> JDBC.update("UPDATE journal_entry SET type = 'DEPOSIT' WHERE id = ?", id))
        .hasStackTraceContaining("append-only");
    assertThatThrownBy(() -> JDBC.update("DELETE FROM journal_entry WHERE id = ?", id))
        .hasStackTraceContaining("append-only");
    assertThatThrownBy(() -> JDBC.execute("TRUNCATE posting, journal_entry"))
        .hasStackTraceContaining("append-only");
    assertThat(entryExists(id)).isTrue();
  }
}
