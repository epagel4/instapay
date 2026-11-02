package com.instapay.payment.domain.transfer;

import java.util.Objects;
import java.util.UUID;

public record RefundId(UUID value) {

  public RefundId {
    Objects.requireNonNull(value, "refundId");
  }

  public static RefundId newId() {
    return new RefundId(UUID.randomUUID());
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
