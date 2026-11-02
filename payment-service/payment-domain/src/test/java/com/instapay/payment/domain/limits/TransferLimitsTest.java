package com.instapay.payment.domain.limits;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.instapay.payment.domain.shared.Money;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TransferLimitsTest {

  final TransferLimits limits =
      new TransferLimits(Money.of("500"), Money.of("2000"), Money.of("1000"));

  @Test
  void allowsUpToEachLimitInclusive() {
    assertThatCode(
            () -> limits.check(Money.of("500"), Money.of("1500"), Optional.of(Money.of("500"))))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @CsvSource({
    "500.01, 0,       ,       limit.per_transaction_exceeded",
    "100,    1900.01, ,       limit.daily_exceeded",
    "100,    0,       900.01, limit.nightly_exceeded"
  })
  void rejectsAboveLimits(String amount, String today, String tonight, String code) {
    Optional<Money> night = Optional.ofNullable(tonight).map(Money::of);

    assertThatThrownBy(() -> limits.check(Money.of(amount), Money.of(today), night))
        .extracting("code")
        .isEqualTo(code);
  }

  @Test
  void nightlyLimitIgnoredDuringTheDay() {
    assertThatCode(() -> limits.check(Money.of("500"), Money.of("1500"), Optional.empty()))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @CsvSource({
    "0, 1000, 100",
    "100, 1000, 0",
    "100, 0, 100",
    "1001, 1000, 100",
    "100, 1000, 1001",
    "-1, 1000, 100"
  })
  void limitsMustBePositiveAndConsistent(String perTx, String daily, String nightly) {
    assertThatThrownBy(
            () -> new TransferLimits(Money.of(perTx), Money.of(daily), Money.of(nightly)))
        .extracting("code")
        .isEqualTo("limits.invalid");
  }
}
