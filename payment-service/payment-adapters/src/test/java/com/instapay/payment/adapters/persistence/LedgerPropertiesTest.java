package com.instapay.payment.adapters.persistence;

import static com.instapay.payment.adapters.persistence.LedgerDatabase.REPOSITORY;
import static com.instapay.payment.adapters.persistence.LedgerDatabase.TX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.instapay.payment.domain.account.AccountId;
import com.instapay.payment.domain.ledger.JournalEntry;
import com.instapay.payment.domain.ledger.JournalEntryId;
import com.instapay.payment.domain.ledger.Posting;
import com.instapay.payment.domain.ledger.SystemAccounts;
import com.instapay.payment.domain.shared.Money;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/** The zero-sum invariant, checked against the real schema after random sequences of writes. */
class LedgerPropertiesTest {

  static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
  static final int ACCOUNTS = 5;

  enum Kind {
    DEPOSIT,
    TRANSFER,
    /** Malicious raw insert that bypasses the domain: must be rejected by the database. */
    RAW_UNBALANCED
  }

  record Op(Kind kind, int from, int to, long cents) {}

  @Provide
  Arbitrary<List<Op>> operations() {
    return Combinators.combine(
            Arbitraries.of(Kind.class),
            Arbitraries.integers().between(0, ACCOUNTS - 1),
            Arbitraries.integers().between(0, ACCOUNTS - 1),
            Arbitraries.longs().between(1, 1_000_000_00L))
        .as(Op::new)
        .filter(op -> op.kind() != Kind.TRANSFER || op.from() != op.to())
        .list()
        .ofMaxSize(40);
  }

  @Property(tries = 100)
  void sumOfAllPostingsIsAlwaysZero(@ForAll("operations") List<Op> ops) {
    List<AccountId> accounts =
        IntStream.range(0, ACCOUNTS).mapToObj(i -> AccountId.newId()).toList();
    Map<AccountId, Money> expected = new HashMap<>();

    for (Op op : ops) {
      Money amount = Money.ofCents(op.cents());
      switch (op.kind()) {
        case DEPOSIT ->
            append(
                JournalEntry.deposit(JournalEntryId.newId(), accounts.get(op.to()), amount, NOW),
                expected);
        case TRANSFER ->
            append(
                JournalEntry.transfer(
                    JournalEntryId.newId(),
                    UUID.randomUUID(),
                    accounts.get(op.from()),
                    accounts.get(op.to()),
                    amount,
                    NOW),
                expected);
        case RAW_UNBALANCED ->
            assertThatThrownBy(
                    () ->
                        LedgerDatabase.insertRawEntry(
                            UUID.randomUUID(), op.cents(), -op.cents() - 1))
                .hasStackTraceContaining("unbalanced");
      }
    }

    assertThat(LedgerDatabase.sumOfAllPostings()).isZero();
    assertThat(LedgerDatabase.unbalancedEntries()).isZero();
    for (AccountId account : accounts) {
      assertThat(REPOSITORY.balanceOf(account))
          .isEqualTo(expected.getOrDefault(account, Money.ZERO));
    }
    assertThat(REPOSITORY.balanceOf(SystemAccounts.FUNDING)).isLessThanOrEqualTo(Money.ZERO);
  }

  private static void append(JournalEntry entry, Map<AccountId, Money> expected) {
    TX.executeWithoutResult(s -> REPOSITORY.append(entry));
    for (Posting p : entry.postings()) {
      expected.merge(p.account(), p.amount(), Money::plus);
    }
  }
}
