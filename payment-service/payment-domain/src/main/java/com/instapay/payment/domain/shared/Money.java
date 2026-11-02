package com.instapay.payment.domain.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Amount in BRL, stored as an exact number of cents (see ADR-004). May be negative so that ledger
 * entries can be signed; operations that require positive amounts validate it themselves.
 */
public record Money(long cents) implements Comparable<Money> {

  public static final Money ZERO = new Money(0);
  private static final int SCALE = 2;

  public static Money ofCents(long cents) {
    return new Money(cents);
  }

  /** Exact conversion: rejects amounts with more than two decimal places. */
  public static Money of(String amount) {
    try {
      return of(new BigDecimal(amount));
    } catch (NumberFormatException e) {
      throw new DomainException("money.invalid", "Not a decimal amount: " + amount);
    }
  }

  /** Exact conversion: rejects amounts with more than two decimal places. */
  public static Money of(BigDecimal amount) {
    return of(amount, RoundingMode.UNNECESSARY);
  }

  /** Conversion with an explicit rounding decision for amounts with more than two decimals. */
  public static Money of(BigDecimal amount, RoundingMode rounding) {
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(rounding, "rounding");
    try {
      return new Money(amount.setScale(SCALE, rounding).unscaledValue().longValueExact());
    } catch (ArithmeticException e) {
      throw new DomainException(
          "money.invalid", "Amount needs rounding or is out of range: " + amount.toPlainString());
    }
  }

  public Money plus(Money other) {
    return exact(() -> Math.addExact(cents, other.cents));
  }

  public Money minus(Money other) {
    return exact(() -> Math.subtractExact(cents, other.cents));
  }

  public Money negate() {
    return exact(() -> Math.negateExact(cents));
  }

  public boolean isPositive() {
    return cents > 0;
  }

  public boolean isNegative() {
    return cents < 0;
  }

  public boolean isZero() {
    return cents == 0;
  }

  public boolean isGreaterThan(Money other) {
    return compareTo(other) > 0;
  }

  public BigDecimal toBigDecimal() {
    return BigDecimal.valueOf(cents, SCALE);
  }

  @Override
  public int compareTo(Money other) {
    return Long.compare(cents, other.cents);
  }

  @Override
  public String toString() {
    return toBigDecimal().toPlainString();
  }

  private static Money exact(java.util.function.LongSupplier operation) {
    try {
      return new Money(operation.getAsLong());
    } catch (ArithmeticException e) {
      throw new DomainException("money.overflow", "Monetary overflow");
    }
  }
}
