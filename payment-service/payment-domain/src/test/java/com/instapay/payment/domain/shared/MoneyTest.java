package com.instapay.payment.domain.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MoneyTest {

  private static final long BOUND = 1_000_000_000_000L; // R$ 10 bi: no overflow in sums

  @Property
  void additionIsCommutativeAndAssociative(
      @ForAll @LongRange(min = -BOUND, max = BOUND) long a,
      @ForAll @LongRange(min = -BOUND, max = BOUND) long b,
      @ForAll @LongRange(min = -BOUND, max = BOUND) long c) {
    Money x = Money.ofCents(a), y = Money.ofCents(b), z = Money.ofCents(c);

    assertThat(x.plus(y)).isEqualTo(y.plus(x));
    assertThat(x.plus(y).plus(z)).isEqualTo(x.plus(y.plus(z)));
  }

  @Property
  void minusUndoesPlusAndNegateSumsToZero(
      @ForAll @LongRange(min = -BOUND, max = BOUND) long a,
      @ForAll @LongRange(min = -BOUND, max = BOUND) long b) {
    Money x = Money.ofCents(a), y = Money.ofCents(b);

    assertThat(x.plus(y).minus(y)).isEqualTo(x);
    assertThat(x.plus(x.negate())).isEqualTo(Money.ZERO);
  }

  @Property
  void bigDecimalRoundTripIsExact(@ForAll long cents) {
    Money money = Money.ofCents(cents);

    assertThat(Money.of(money.toBigDecimal())).isEqualTo(money);
    assertThat(Money.of(money.toString())).isEqualTo(money);
  }

  @ParameterizedTest
  @CsvSource({"10, 1000", "10.5, 1050", "10.50, 1050", "0.01, 1", "-3.20, -320", "0, 0"})
  void parsesExactAmounts(String amount, long cents) {
    assertThat(Money.of(amount).cents()).isEqualTo(cents);
  }

  @Test
  void rejectsAmountsThatNeedImplicitRounding() {
    assertThatThrownBy(() -> Money.of("10.005"))
        .isInstanceOf(DomainException.class)
        .extracting("code")
        .isEqualTo("money.invalid");
  }

  @ParameterizedTest
  @CsvSource({
    "10.005, HALF_EVEN, 1000",
    "10.015, HALF_EVEN, 1002",
    "10.005, HALF_UP, 1001",
    "10.009, DOWN, 1000"
  })
  void roundsOnlyWithExplicitMode(String amount, RoundingMode mode, long cents) {
    assertThat(Money.of(new BigDecimal(amount), mode).cents()).isEqualTo(cents);
  }

  @Test
  void rejectsGarbageAndOutOfRange() {
    assertThatThrownBy(() -> Money.of("abc")).isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> Money.of(new BigDecimal("1e30"))).isInstanceOf(DomainException.class);
  }

  @Test
  void overflowFailsInsteadOfWrapping() {
    Money max = Money.ofCents(Long.MAX_VALUE);

    assertThatThrownBy(() -> max.plus(Money.ofCents(1)))
        .extracting("code")
        .isEqualTo("money.overflow");
    assertThatThrownBy(() -> Money.ofCents(Long.MIN_VALUE).negate())
        .extracting("code")
        .isEqualTo("money.overflow");
    assertThatThrownBy(() -> Money.ofCents(Long.MIN_VALUE).minus(Money.ofCents(1)))
        .extracting("code")
        .isEqualTo("money.overflow");
  }

  @Test
  void comparisonsAndSign() {
    Money ten = Money.of("10.00");

    assertThat(ten.isPositive()).isTrue();
    assertThat(ten.negate().isNegative()).isTrue();
    assertThat(Money.ZERO.isZero()).isTrue();
    assertThat(ten.isGreaterThan(Money.of("9.99"))).isTrue();
    assertThat(ten.isGreaterThan(ten)).isFalse();
    assertThat(ten).isEqualByComparingTo(Money.ofCents(1000));
    assertThat(ten.toString()).isEqualTo("10.00");
  }
}
