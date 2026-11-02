package com.instapay.payment.domain.shared;

import java.util.Objects;

/** Institution identifier in the Brazilian payment system (8 digits). */
public record Ispb(String value) {

  public Ispb {
    Objects.requireNonNull(value, "ispb");
    if (!value.matches("\\d{8}")) {
      throw new DomainException("ispb.invalid", "ISPB must have 8 digits");
    }
  }
}
