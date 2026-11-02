package com.instapay.payment.domain.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IspbTest {

  @Test
  void acceptsEightDigits() {
    assertThat(new Ispb("00000000").value()).isEqualTo("00000000");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "1234567", "123456789", "1234567a"})
  void rejectsOtherFormats(String raw) {
    assertThatThrownBy(() -> new Ispb(raw)).isInstanceOf(DomainException.class);
  }
}
