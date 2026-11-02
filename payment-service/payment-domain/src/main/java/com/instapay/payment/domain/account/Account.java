package com.instapay.payment.domain.account;

import com.instapay.payment.domain.shared.DomainException;
import com.instapay.payment.domain.shared.Ispb;
import com.instapay.payment.domain.shared.Money;
import java.util.Objects;

/**
 * Checking account. Deliberately has no balance field: the balance is always derived from the
 * ledger, and is passed in by the caller where a rule depends on it. {@code version} is the
 * aggregate version used for optimistic concurrency control (ADR-002).
 */
public final class Account {

  private final AccountId id;
  private final Ispb institution;
  private final AccountHolder holder;
  private final long version;
  private AccountStatus status;

  private Account(
      AccountId id, Ispb institution, AccountHolder holder, AccountStatus status, long version) {
    this.id = Objects.requireNonNull(id, "id");
    this.institution = Objects.requireNonNull(institution, "institution");
    this.holder = Objects.requireNonNull(holder, "holder");
    this.status = Objects.requireNonNull(status, "status");
    this.version = version;
  }

  public static Account open(AccountId id, Ispb institution, AccountHolder holder) {
    return new Account(id, institution, holder, AccountStatus.ACTIVE, 0);
  }

  /** Rehydrates persisted state; no transition rules applied. */
  public static Account restore(
      AccountId id, Ispb institution, AccountHolder holder, AccountStatus status, long version) {
    return new Account(id, institution, holder, status, version);
  }

  public void block() {
    requireStatus(AccountStatus.ACTIVE, "block");
    status = AccountStatus.BLOCKED;
  }

  public void unblock() {
    requireStatus(AccountStatus.BLOCKED, "unblock");
    status = AccountStatus.ACTIVE;
  }

  public void close(Money currentBalance) {
    if (status == AccountStatus.CLOSED) {
      throw new DomainException("account.closed", "Account already closed");
    }
    if (!currentBalance.isZero()) {
      throw new DomainException(
          "account.balance.not_zero", "Account balance must be zero to close");
    }
    status = AccountStatus.CLOSED;
  }

  public void ensureCanDebit() {
    if (status != AccountStatus.ACTIVE) {
      throw new DomainException("account.debit.not_allowed", "Account " + status + " cannot send");
    }
  }

  public void ensureCanCredit() {
    if (status == AccountStatus.CLOSED) {
      throw new DomainException("account.credit.not_allowed", "Closed account cannot receive");
    }
  }

  public AccountRef ref() {
    return new AccountRef(institution, id);
  }

  public AccountId id() {
    return id;
  }

  public Ispb institution() {
    return institution;
  }

  public AccountHolder holder() {
    return holder;
  }

  public AccountStatus status() {
    return status;
  }

  public long version() {
    return version;
  }

  private void requireStatus(AccountStatus expected, String action) {
    if (status != expected) {
      throw new DomainException(
          "account.transition.invalid", "Cannot " + action + " account in status " + status);
    }
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof Account other && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return id.hashCode();
  }
}
