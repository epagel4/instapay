package com.instapay.payment.domain.ledger;

import com.instapay.payment.domain.account.AccountId;
import com.instapay.payment.domain.shared.DomainException;
import com.instapay.payment.domain.shared.Money;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Double-entry accounting transaction: an immutable set of postings that always sums to zero, so
 * money is only ever moved, never created or destroyed. {@code reference} links it to the business
 * operation (e.g. the transfer id).
 */
public record JournalEntry(
    JournalEntryId id,
    JournalEntryType type,
    UUID reference,
    Instant occurredAt,
    List<Posting> postings) {

  public JournalEntry {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(reference, "reference");
    Objects.requireNonNull(occurredAt, "occurredAt");
    postings = List.copyOf(postings);
    if (postings.size() < 2) {
      throw new DomainException("ledger.entry.too_few_postings", "Needs at least two postings");
    }
    if (postings.stream().map(Posting::account).distinct().count() != postings.size()) {
      throw new DomainException("ledger.entry.duplicate_account", "One posting per account");
    }
    if (!sum(postings).isZero()) {
      throw new DomainException("ledger.entry.unbalanced", "Postings must sum to zero");
    }
  }

  public static JournalEntry transfer(
      JournalEntryId id, UUID reference, AccountId from, AccountId to, Money amount, Instant at) {
    return move(id, JournalEntryType.TRANSFER, reference, from, to, amount, at);
  }

  /** Two-posting entry moving {@code amount} from one account to another. */
  public static JournalEntry move(
      JournalEntryId id,
      JournalEntryType type,
      UUID reference,
      AccountId from,
      AccountId to,
      Money amount,
      Instant at) {
    requirePositive(amount);
    return new JournalEntry(
        id,
        type,
        reference,
        at,
        List.of(new Posting(from, amount.negate()), new Posting(to, amount)));
  }

  public static JournalEntry deposit(JournalEntryId id, AccountId to, Money amount, Instant at) {
    requirePositive(amount);
    return new JournalEntry(
        id,
        JournalEntryType.DEPOSIT,
        id.value(),
        at,
        List.of(new Posting(SystemAccounts.FUNDING, amount.negate()), new Posting(to, amount)));
  }

  /** Effect of this entry on the given account's balance (zero if it is not involved). */
  public Money netAmountFor(AccountId account) {
    return sum(postings.stream().filter(p -> p.account().equals(account)).toList());
  }

  private static Money sum(List<Posting> postings) {
    return postings.stream().map(Posting::amount).reduce(Money.ZERO, Money::plus);
  }

  private static void requirePositive(Money amount) {
    if (!amount.isPositive()) {
      throw new DomainException("ledger.amount.not_positive", "Amount must be positive");
    }
  }
}
