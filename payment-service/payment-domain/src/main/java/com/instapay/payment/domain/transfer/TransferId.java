package com.instapay.payment.domain.transfer;

import java.util.Objects;
import java.util.UUID;

public record TransferId(UUID value) {

  public TransferId {
    Objects.requireNonNull(value, "transferId");
  }

  public static TransferId newId() {
    return new TransferId(UUID.randomUUID());
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
