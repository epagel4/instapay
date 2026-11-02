package com.instapay.payment.domain.transfer;

import com.instapay.payment.domain.account.AccountRef;
import com.instapay.payment.domain.shared.DomainException;
import com.instapay.payment.domain.shared.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A transfer between two accounts. PENDING is the only non-terminal state; time always comes from
 * the caller (an injected Clock), never from the system clock.
 */
public final class Transfer {

  /** Pix allows returning a payment up to 90 days after it was made. */
  public static final Duration REFUND_WINDOW = Duration.ofDays(90);

  private final TransferId id;
  private final AccountRef debtor;
  private final AccountRef creditor;
  private final Money amount;
  private final Instant createdAt;
  private TransferStatus status;
  private Instant finishedAt;
  private String failureReason;

  private Transfer(
      TransferId id,
      AccountRef debtor,
      AccountRef creditor,
      Money amount,
      Instant createdAt,
      TransferStatus status,
      Instant finishedAt,
      String failureReason) {
    this.id = Objects.requireNonNull(id, "id");
    this.debtor = Objects.requireNonNull(debtor, "debtor");
    this.creditor = Objects.requireNonNull(creditor, "creditor");
    this.amount = Objects.requireNonNull(amount, "amount");
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    this.status = Objects.requireNonNull(status, "status");
    this.finishedAt = finishedAt;
    this.failureReason = failureReason;
  }

  public static Transfer request(
      TransferId id, AccountRef debtor, AccountRef creditor, Money amount, Instant now) {
    if (!amount.isPositive()) {
      throw new DomainException("transfer.amount.not_positive", "Transfer amount must be positive");
    }
    if (debtor.equals(creditor)) {
      throw new DomainException("transfer.same_account", "Debtor and creditor must differ");
    }
    return new Transfer(id, debtor, creditor, amount, now, TransferStatus.PENDING, null, null);
  }

  /** Rehydrates persisted state; no transition rules applied. */
  public static Transfer restore(
      TransferId id,
      AccountRef debtor,
      AccountRef creditor,
      Money amount,
      Instant createdAt,
      TransferStatus status,
      Instant finishedAt,
      String failureReason) {
    return new Transfer(id, debtor, creditor, amount, createdAt, status, finishedAt, failureReason);
  }

  public void complete(Instant now) {
    requirePending();
    status = TransferStatus.COMPLETED;
    finishedAt = Objects.requireNonNull(now, "now");
  }

  public void fail(String reason, Instant now) {
    if (reason == null || reason.isBlank()) {
      throw new DomainException("transfer.failure_reason.required", "Failure reason is required");
    }
    requirePending();
    status = TransferStatus.FAILED;
    failureReason = reason;
    finishedAt = Objects.requireNonNull(now, "now");
  }

  /**
   * Returns {@code amount} (total or partial) of this transfer from creditor to debtor.
   *
   * @param alreadyRefunded sum of previous refunds of this transfer, read under a lock on it
   */
  public Refund refund(
      RefundId refundId, Money refundAmount, Money alreadyRefunded, String reason, Instant now) {
    if (!isInternal()) {
      throw new DomainException(
          "refund.external_not_supported",
          "Transfers to other institutions are refunded by the receiving bank");
    }
    if (status != TransferStatus.COMPLETED) {
      throw new DomainException(
          "refund.transfer_not_completed", "Only completed transfers can be refunded");
    }
    if (!refundAmount.isPositive()) {
      throw new DomainException("refund.amount.not_positive", "Refund amount must be positive");
    }
    if (alreadyRefunded.plus(refundAmount).isGreaterThan(amount)) {
      throw new DomainException(
          "refund.exceeds_original",
          "Refunds would exceed the original amount (" + amount.minus(alreadyRefunded) + " left)");
    }
    if (now.isAfter(finishedAt.plus(REFUND_WINDOW))) {
      throw new DomainException("refund.window_expired", "Refund window of 90 days has passed");
    }
    return new Refund(refundId, id, refundAmount, reason, now);
  }

  /** Both accounts held by the same institution: settled locally, no saga needed. */
  public boolean isInternal() {
    return debtor.institution().equals(creditor.institution());
  }

  private void requirePending() {
    if (status != TransferStatus.PENDING) {
      throw new DomainException(
          "transfer.transition.invalid", "Transfer " + id + " is already " + status);
    }
  }

  public TransferId id() {
    return id;
  }

  public AccountRef debtor() {
    return debtor;
  }

  public AccountRef creditor() {
    return creditor;
  }

  public Money amount() {
    return amount;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public TransferStatus status() {
    return status;
  }

  public Optional<Instant> finishedAt() {
    return Optional.ofNullable(finishedAt);
  }

  public Optional<String> failureReason() {
    return Optional.ofNullable(failureReason);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof Transfer other && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return id.hashCode();
  }
}
