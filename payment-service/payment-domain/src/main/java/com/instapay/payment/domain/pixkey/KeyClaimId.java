package com.instapay.payment.domain.pixkey;

import java.util.Objects;
import java.util.UUID;

public record KeyClaimId(UUID value) {

  public KeyClaimId {
    Objects.requireNonNull(value, "keyClaimId");
  }

  public static KeyClaimId newId() {
    return new KeyClaimId(UUID.randomUUID());
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
