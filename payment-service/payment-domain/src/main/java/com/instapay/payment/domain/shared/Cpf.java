package com.instapay.payment.domain.shared;

import java.util.Objects;

/** Brazilian individual taxpayer id. Normalized to 11 digits; check digits are validated. */
public record Cpf(String value) {

  public Cpf {
    Objects.requireNonNull(value, "cpf");
    value = value.strip().replace(".", "").replace("-", "");
    if (!value.matches("\\d{11}")
        || value.chars().distinct().count() == 1
        || !checkDigitsOk(value)) {
      throw new DomainException("cpf.invalid", "Invalid CPF");
    }
  }

  public static Cpf of(String raw) {
    return new Cpf(raw);
  }

  public String formatted() {
    return "%s.%s.%s-%s"
        .formatted(
            value.substring(0, 3),
            value.substring(3, 6),
            value.substring(6, 9),
            value.substring(9));
  }

  /** Masked to avoid leaking personal data (LGPD) into logs. */
  @Override
  public String toString() {
    return "***." + value.substring(3, 6) + "." + value.substring(6, 9) + "-**";
  }

  static int checkDigit(String digits, int length) {
    int sum = 0;
    for (int i = 0; i < length; i++) {
      sum += (digits.charAt(i) - '0') * (length + 1 - i);
    }
    return sum * 10 % 11 % 10;
  }

  private static boolean checkDigitsOk(String d) {
    return checkDigit(d, 9) == d.charAt(9) - '0' && checkDigit(d, 10) == d.charAt(10) - '0';
  }
}
