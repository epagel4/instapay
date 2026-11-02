package com.instapay.settlement.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A request to move money between two participant institutions. */
public record SettlementOrder(
    UUID transferId,
    String debtorIspb,
    UUID debtorAccount,
    String creditorIspb,
    UUID creditorAccount,
    long amountCents,
    Instant expiresAt) {

  public SettlementOrder {
    Objects.requireNonNull(transferId, "transferId");
    Objects.requireNonNull(debtorIspb, "debtorIspb");
    Objects.requireNonNull(debtorAccount, "debtorAccount");
    Objects.requireNonNull(creditorIspb, "creditorIspb");
    Objects.requireNonNull(creditorAccount, "creditorAccount");
    Objects.requireNonNull(expiresAt, "expiresAt");
  }
}
