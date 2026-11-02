package com.instapay.payment.domain.transfer;

import com.instapay.payment.domain.shared.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/** Snapshot of a transfer at a lifecycle step. */
public record TransferEvent(
    Type type,
    UUID transferId,
    String debtorIspb,
    UUID debtorAccount,
    String creditorIspb,
    UUID creditorAccount,
    long amountCents,
    TransferStatus status,
    String detail,
    Instant occurredAt)
    implements DomainEvent {

  public enum Type {
    COMPLETED,
    FAILED,
    SETTLEMENT_REQUESTED
  }

  public static TransferEvent of(Type type, Transfer t, String detail, Instant at) {
    return new TransferEvent(
        type,
        t.id().value(),
        t.debtor().institution().value(),
        t.debtor().account().value(),
        t.creditor().institution().value(),
        t.creditor().account().value(),
        t.amount().cents(),
        t.status(),
        detail,
        at);
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
    return "Transfer"
        + switch (type) {
          case COMPLETED -> "Completed";
          case FAILED -> "Failed";
          case SETTLEMENT_REQUESTED -> "SettlementRequested";
        };
  }
}
