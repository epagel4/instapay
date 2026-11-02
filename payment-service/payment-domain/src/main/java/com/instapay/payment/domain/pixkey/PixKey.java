package com.instapay.payment.domain.pixkey;

import java.util.Objects;
import java.util.UUID;

/**
 * Payment key in canonical form (CPF digits only, lower-case e-mail, E.164 phone, lower-case UUID),
 * so equality means "same key" for uniqueness checks.
 */
public record PixKey(PixKeyType type, String value) {

  public PixKey {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(value, "value");
    value = type.normalize(value);
  }

  public static PixKey of(PixKeyType type, String raw) {
    return new PixKey(type, raw);
  }

  public static PixKey random() {
    return new PixKey(PixKeyType.RANDOM, UUID.randomUUID().toString());
  }

  /** Masked to avoid leaking personal data (LGPD) into logs. */
  @Override
  public String toString() {
    return type + ":" + value.substring(0, Math.min(3, value.length())) + "***";
  }
}
