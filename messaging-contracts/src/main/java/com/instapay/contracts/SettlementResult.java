package com.instapay.contracts;

import java.time.Instant;
import java.util.UUID;

/** The clearing house decision. Re-sent unchanged if the same request is delivered again. */
public record SettlementResult(UUID transferId, Status status, String reason, Instant decidedAt) {

  public enum Status {
    SETTLED,
    REJECTED
  }
}
