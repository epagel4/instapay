package com.instapay.settlement.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The clearing house decision for one transfer. Decided once; the same decision is returned for
 * every redelivery of the order.
 */
public record Settlement(
    UUID transferId,
    String debtorIspb,
    String creditorIspb,
    UUID creditorAccount,
    long amountCents,
    SettlementStatus status,
    String reason,
    Instant decidedAt) {

  public Settlement {
    Objects.requireNonNull(transferId, "transferId");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(decidedAt, "decidedAt");
  }

  /**
   * Never settles after the order expired: the sender compensates on timeout, so settling late
   * would move the money twice.
   */
  public static Settlement decide(SettlementOrder order, Set<String> participants, Instant now) {
    String rejection = rejectionReason(order, participants, now);
    return new Settlement(
        order.transferId(),
        order.debtorIspb(),
        order.creditorIspb(),
        order.creditorAccount(),
        order.amountCents(),
        rejection == null ? SettlementStatus.SETTLED : SettlementStatus.REJECTED,
        rejection,
        now);
  }

  private static String rejectionReason(SettlementOrder o, Set<String> participants, Instant now) {
    if (now.isAfter(o.expiresAt())) {
      return "expired";
    }
    if (o.amountCents() <= 0) {
      return "invalid_amount";
    }
    if (!participants.contains(o.debtorIspb())) {
      return "unknown_debtor_institution";
    }
    if (!participants.contains(o.creditorIspb())) {
      return "unknown_creditor_institution";
    }
    if (o.debtorIspb().equals(o.creditorIspb())) {
      return "same_institution";
    }
    return null;
  }

  public boolean isSettled() {
    return status == SettlementStatus.SETTLED;
  }
}
