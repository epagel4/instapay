package com.instapay.payment.domain.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.instapay.payment.domain.account.AccountId;
import com.instapay.payment.domain.shared.DomainException;
import com.instapay.payment.domain.shared.Money;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

class JournalEntryTest {

  static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
  static final long MAX_CENTS = 100_000_000_00L; // R$ 100 mi per posting

  final AccountId alice = AccountId.newId();
  final AccountId bob = AccountId.newId();

  // ---------- properties ----------

  /** n-1 random postings plus one that offsets them: always a valid, zero-sum entry. */
  @Provide
  Arbitrary<List<Posting>> balancedPostings() {
    Arbitrary<Long> nonZero =
        Arbitraries.longs().between(-MAX_CENTS, MAX_CENTS).filter(c -> c != 0);
    return nonZero
        .list()
        .ofMinSize(1)
        .ofMaxSize(10)
        .filter(amounts -> amounts.stream().mapToLong(Long::longValue).sum() != 0)
        .map(
            amounts -> {
              List<Posting> postings = new ArrayList<>();
              amounts.forEach(c -> postings.add(new Posting(AccountId.newId(), Money.ofCents(c))));
              long offset = -amounts.stream().mapToLong(Long::longValue).sum();
              postings.add(new Posting(AccountId.newId(), Money.ofCents(offset)));
              return postings;
            });
  }

  @Property
  void everyAcceptedEntrySumsToZero(@ForAll("balancedPostings") List<Posting> postings) {
    JournalEntry entry = entry(postings);

    Money total = entry.postings().stream().map(Posting::amount).reduce(Money.ZERO, Money::plus);
    assertThat(total).isEqualTo(Money.ZERO);
    assertThat(entry.postings()).anyMatch(Posting::isDebit).anyMatch(p -> !p.isDebit());
  }

  @Property
  void anyImbalanceIsRejected(
      @ForAll("balancedPostings") List<Posting> postings,
      @ForAll @IntRange(min = 0, max = 10) int index,
      @ForAll @LongRange(min = 1, max = MAX_CENTS) long delta) {
    List<Posting> tampered = new ArrayList<>(postings);
    int i = index % tampered.size();
    Posting original = tampered.get(i);
    long changed = original.amount().cents() + delta;
    tampered.set(i, new Posting(original.account(), Money.ofCents(changed == 0 ? 1 : changed)));

    assertThatThrownBy(() -> entry(tampered))
        .isInstanceOf(DomainException.class)
        .extracting("code")
        .isEqualTo("ledger.entry.unbalanced");
  }

  record Op(boolean deposit, int from, int to, long cents) {}

  @Provide
  Arbitrary<List<Op>> operations() {
    return Combinators.combine(
            Arbitraries.of(true, false),
            Arbitraries.integers().between(0, 4),
            Arbitraries.integers().between(0, 4),
            Arbitraries.longs().between(1, MAX_CENTS))
        .as(Op::new)
        .filter(op -> op.deposit() || op.from() != op.to())
        .list()
        .ofMaxSize(200);
  }

  /**
   * Over any sequence of deposits and transfers, money is conserved: the balances of all accounts
   * (system accounts included) sum to zero, and customers hold exactly what came in via deposits.
   */
  @Property
  void moneyIsConservedAcrossAnySequence(@ForAll("operations") List<Op> ops) {
    List<AccountId> customers =
        List.of(alice, bob, AccountId.newId(), AccountId.newId(), AccountId.newId());
    List<JournalEntry> journal = new ArrayList<>();
    long deposited = 0;
    for (Op op : ops) {
      Money amount = Money.ofCents(op.cents());
      if (op.deposit()) {
        journal.add(
            JournalEntry.deposit(JournalEntryId.newId(), customers.get(op.to()), amount, NOW));
        deposited += op.cents();
      } else {
        journal.add(
            JournalEntry.transfer(
                JournalEntryId.newId(),
                UUID.randomUUID(),
                customers.get(op.from()),
                customers.get(op.to()),
                amount,
                NOW));
      }
    }

    Map<AccountId, Money> balances = new HashMap<>();
    for (JournalEntry entry : journal) {
      for (Posting p : entry.postings()) {
        balances.merge(p.account(), p.amount(), Money::plus);
      }
    }
    Money all = balances.values().stream().reduce(Money.ZERO, Money::plus);
    Money customersTotal =
        customers.stream().map(c -> balanceOf(c, journal)).reduce(Money.ZERO, Money::plus);

    assertThat(all).isEqualTo(Money.ZERO);
    assertThat(customersTotal).isEqualTo(Money.ofCents(deposited));
    assertThat(balanceOf(SystemAccounts.FUNDING, journal)).isEqualTo(Money.ofCents(-deposited));
  }

  // ---------- examples ----------

  @Test
  void transferDebitsSourceAndCreditsDestination() {
    UUID transferId = UUID.randomUUID();
    JournalEntry entry =
        JournalEntry.transfer(
            JournalEntryId.newId(), transferId, alice, bob, Money.of("25.00"), NOW);

    assertThat(entry.type()).isEqualTo(JournalEntryType.TRANSFER);
    assertThat(entry.reference()).isEqualTo(transferId);
    assertThat(entry.occurredAt()).isEqualTo(NOW);
    assertThat(entry.netAmountFor(alice)).isEqualTo(Money.of("-25.00"));
    assertThat(entry.netAmountFor(bob)).isEqualTo(Money.of("25.00"));
    assertThat(entry.netAmountFor(AccountId.newId())).isEqualTo(Money.ZERO);
  }

  @Test
  void depositIsFundedBySystemAccount() {
    JournalEntryId id = JournalEntryId.newId();
    JournalEntry entry = JournalEntry.deposit(id, alice, Money.of("100"), NOW);

    assertThat(entry.type()).isEqualTo(JournalEntryType.DEPOSIT);
    assertThat(entry.reference()).isEqualTo(id.value());
    assertThat(entry.netAmountFor(SystemAccounts.FUNDING)).isEqualTo(Money.of("-100"));
    assertThat(entry.netAmountFor(alice)).isEqualTo(Money.of("100"));
  }

  @Test
  void amountsMustBePositive() {
    assertThatThrownBy(
            () ->
                JournalEntry.transfer(
                    JournalEntryId.newId(), UUID.randomUUID(), alice, bob, Money.ZERO, NOW))
        .extracting("code")
        .isEqualTo("ledger.amount.not_positive");
    assertThatThrownBy(
            () -> JournalEntry.deposit(JournalEntryId.newId(), alice, Money.of("-1"), NOW))
        .extracting("code")
        .isEqualTo("ledger.amount.not_positive");
  }

  @Test
  void structuralInvariants() {
    Money one = Money.of("1");
    assertThatThrownBy(() -> entry(List.of(new Posting(alice, one))))
        .extracting("code")
        .isEqualTo("ledger.entry.too_few_postings");
    assertThatThrownBy(
            () -> entry(List.of(new Posting(alice, one), new Posting(alice, one.negate()))))
        .extracting("code")
        .isEqualTo("ledger.entry.duplicate_account");
    assertThatThrownBy(() -> new Posting(alice, Money.ZERO))
        .extracting("code")
        .isEqualTo("ledger.posting.zero");
  }

  @Test
  void postingsAreImmutable() {
    List<Posting> source =
        new ArrayList<>(
            List.of(new Posting(alice, Money.of("-1")), new Posting(bob, Money.of("1"))));
    JournalEntry entry = entry(source);
    source.clear();

    assertThat(entry.postings()).hasSize(2);
    assertThatThrownBy(() -> entry.postings().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  private static JournalEntry entry(List<Posting> postings) {
    return new JournalEntry(
        JournalEntryId.newId(), JournalEntryType.TRANSFER, UUID.randomUUID(), NOW, postings);
  }

  private static Money balanceOf(AccountId account, List<JournalEntry> journal) {
    return journal.stream().map(e -> e.netAmountFor(account)).reduce(Money.ZERO, Money::plus);
  }
}
