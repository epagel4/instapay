package com.instapay.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Asks the clearing house to settle a transfer between institutions. The clearing house must not
 * settle after {@code expiresAt}: that is what lets the sender compensate safely on timeout.
 */
public record SettlementRequest(
    UUID transferId,
    String debtorIspb,
    UUID debtorAccount,
    String creditorIspb,
    UUID creditorAccount,
    long amountCents,
    Instant requestedAt,
    Instant expiresAt) {}
