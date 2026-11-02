package com.instapay.payment.domain.transfer;

import com.instapay.payment.domain.shared.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/** Recorded on the original transfer's audit trail. */
public record RefundEvent(
    UUID refundId,
    UUID transferId,
    long amountCents,
    long totalRefundedCents,
    String reason,
    Instant occurredAt)
    implements DomainEvent {

  public static RefundEvent of(Refund refund, long totalRefundedCents) {
    return new RefundEvent(
        refund.id().value(),
        refund.originalTransfer().value(),
        refund.amount().cents(),
        totalRefundedCents,
        refund.reason(),
        refund.createdAt());
  }

  @Override
  public String aggregateType() {
    return "Transfer";
  }

  @Override
  public UUID aggregateId() {
    return transferId;
  }

  @Override
  public String eventType() {
    return "TransferRefunded";
  }
}
