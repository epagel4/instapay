package com.instapay.payment.domain.transfer;

import com.instapay.payment.domain.shared.DomainException;
import com.instapay.payment.domain.shared.Money;
import java.time.Instant;
import java.util.Objects;

/** Money returned by the creditor of {@code originalTransfer} to its debtor. Immutable. */
public record Refund(
    RefundId id, TransferId originalTransfer, Money amount, String reason, Instant createdAt) {

  static final int MAX_REASON_LENGTH = 140;

  public Refund {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(originalTransfer, "originalTransfer");
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(createdAt, "createdAt");
    reason = reason == null ? null : reason.strip();
    if (reason != null && reason.length() > MAX_REASON_LENGTH) {
      throw new DomainException("refund.reason.invalid", "Reason must have at most 140 characters");
    }
  }
}
