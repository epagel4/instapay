package com.instapay.payment.domain.pixkey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.instapay.payment.domain.shared.DomainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PixKeyTest {

  @ParameterizedTest(name = "{0} \"{1}\" -> {2}")
  @CsvSource({
    "CPF,    529.982.247-25,                          52998224725",
    "CPF,    52998224725,                             52998224725",
    "EMAIL,  ' Maria.Silva+pix@Example.COM.br ',      maria.silva+pix@example.com.br",
    "PHONE,  +5511987654321,                          +5511987654321",
    "PHONE,  '+55 (11) 98765-4321',                   +5511987654321",
    "PHONE,  +14155552671,                            +14155552671",
    "RANDOM, 123E4567-E89B-42D3-A456-426614174000,    123e4567-e89b-42d3-a456-426614174000"
  })
  void normalizesValidKeys(PixKeyType type, String raw, String canonical) {
    assertThat(PixKey.of(type, raw).value()).isEqualTo(canonical);
  }

  @ParameterizedTest(name = "{0} \"{1}\" is invalid")
  @CsvSource({
    "CPF,    529.982.247-24",
    "EMAIL,  maria",
    "EMAIL,  maria@",
    "EMAIL,  @example.com",
    "EMAIL,  maria@example",
    "EMAIL,  maria silva@example.com",
    "PHONE,  5511987654321",
    "PHONE,  +0511987654321",
    "PHONE,  +5511987654321234",
    "PHONE,  +5511abc",
    "RANDOM, not-a-uuid",
    "RANDOM, 1-1-1-1-1"
  })
  void rejectsInvalidKeys(PixKeyType type, String raw) {
    assertThatThrownBy(() -> PixKey.of(type, raw)).isInstanceOf(DomainException.class);
  }

  @Test
  void rejectsEmailLongerThanDictLimit() {
    String local = "a".repeat(65);
    assertThat(PixKey.of(PixKeyType.EMAIL, local + "@exemplo.com").value()).hasSize(77);
    assertThatThrownBy(() -> PixKey.of(PixKeyType.EMAIL, local + "a@exemplo.com"))
        .extracting("code")
        .isEqualTo("pixkey.invalid");
  }

  @Test
  void sameKeyInDifferentFormatsIsEqual() {
    assertThat(PixKey.of(PixKeyType.EMAIL, "A@B.COM"))
        .isEqualTo(PixKey.of(PixKeyType.EMAIL, "a@b.com"));
  }

  @Test
  void generatesRandomKeys() {
    PixKey key = PixKey.random();

    assertThat(key.type()).isEqualTo(PixKeyType.RANDOM);
    assertThat(PixKey.of(PixKeyType.RANDOM, key.value())).isEqualTo(key);
    assertThat(key).isNotEqualTo(PixKey.random());
  }

  @Test
  void toStringMasksPersonalData() {
    assertThat(PixKey.of(PixKeyType.CPF, "52998224725").toString())
        .isEqualTo("CPF:529***")
        .doesNotContain("52998224725");
  }
}
