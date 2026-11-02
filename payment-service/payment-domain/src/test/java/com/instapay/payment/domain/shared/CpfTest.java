package com.instapay.payment.domain.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CpfTest {

  @Provide
  Arbitrary<String> validCpfs() {
    return Arbitraries.strings()
        .numeric()
        .ofLength(9)
        .filter(base -> base.chars().distinct().count() > 1)
        .map(
            base -> {
              String withFirst = base + Cpf.checkDigit(base, 9);
              return withFirst + Cpf.checkDigit(withFirst, 10);
            });
  }

  @Property
  void acceptsEveryCpfWithCorrectCheckDigits(@ForAll("validCpfs") String digits) {
    assertThat(Cpf.of(digits).value()).isEqualTo(digits);
    assertThat(Cpf.of(Cpf.of(digits).formatted()).value()).isEqualTo(digits);
  }

  // Not every single-digit change in the 9-digit body is caught: "% 11 % 10" maps remainders 0
  // and 10 to the same check digit (jqwik found such a case). Tampering a check digit always is.
  @Property
  void rejectsAnyTamperedCheckDigit(
      @ForAll("validCpfs") String digits,
      @ForAll @IntRange(min = 9, max = 10) int position,
      @ForAll @IntRange(min = 1, max = 9) int delta) {
    char changed = (char) ('0' + (digits.charAt(position) - '0' + delta) % 10);
    String tampered = digits.substring(0, position) + changed + digits.substring(position + 1);
    Assume.that(tampered.chars().distinct().count() > 1);

    assertThatThrownBy(() -> Cpf.of(tampered))
        .isInstanceOf(DomainException.class)
        .extracting("code")
        .isEqualTo("cpf.invalid");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"", "123", "1234567890a", "111.111.111-11", "000.000.000-00", "529982247250"})
  void rejectsMalformed(String raw) {
    assertThatThrownBy(() -> Cpf.of(raw)).isInstanceOf(DomainException.class);
  }

  @Test
  void normalizesFormatsAndMasks() {
    Cpf cpf = Cpf.of(" 529.982.247-25 ");

    assertThat(cpf.value()).isEqualTo("52998224725");
    assertThat(cpf.formatted()).isEqualTo("529.982.247-25");
    assertThat(cpf.toString()).isEqualTo("***.982.247-**").doesNotContain("52998224725");
    assertThat(cpf).isEqualTo(Cpf.of("52998224725"));
  }
}
