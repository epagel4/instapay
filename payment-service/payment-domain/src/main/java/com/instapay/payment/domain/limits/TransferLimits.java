package com.instapay.payment.domain.limits;

import com.instapay.payment.domain.shared.DomainException;
import com.instapay.payment.domain.shared.Money;
import java.util.Objects;
import java.util.Optional;

/**
 * Outgoing transfer limits of an account. The nightly limit caps the total sent during the night
 * window (20h-6h local time); the daily limit caps the calendar day. Both include the new transfer.
 */
public record TransferLimits(Money perTransaction, Money daily, Money nightly) {

  public TransferLimits {
    Objects.requireNonNull(perTransaction, "perTransaction");
    Objects.requireNonNull(daily, "daily");
    Objects.requireNonNull(nightly, "nightly");
    if (!perTransaction.isPositive() || !daily.isPositive() || !nightly.isPositive()) {
      throw new DomainException("limits.invalid", "Limits must be positive");
    }
    if (perTransaction.isGreaterThan(daily) || nightly.isGreaterThan(daily)) {
      throw new DomainException(
          "limits.invalid", "Per-transaction and nightly limits cannot exceed the daily limit");
    }
  }

  /**
   * @param spentToday outgoing total already in today's window
   * @param spentTonight outgoing total already in the current night window; empty during the day
   */
  public void check(Money amount, Money spentToday, Optional<Money> spentTonight) {
    if (amount.isGreaterThan(perTransaction)) {
      throw new DomainException(
          "limit.per_transaction_exceeded", "Above the per-transaction limit of " + perTransaction);
    }
    if (spentToday.plus(amount).isGreaterThan(daily)) {
      throw new DomainException("limit.daily_exceeded", "Above the daily limit of " + daily);
    }
    if (spentTonight.isPresent() && spentTonight.get().plus(amount).isGreaterThan(nightly)) {
      throw new DomainException("limit.nightly_exceeded", "Above the nightly limit of " + nightly);
    }
  }
}
