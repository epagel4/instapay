package com.instapay.settlement.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SettlementTest {

  static final Instant NOW = Instant.parse("2026-11-03T10:00:00Z");
  static final Set<String> PARTICIPANTS = Set.of("00000001", "00000002", "00000003");

  static SettlementOrder order(String debtor, String creditor, long cents, Instant expiresAt) {
    return new SettlementOrder(
        UUID.randomUUID(),
        debtor,
        UUID.randomUUID(),
        creditor,
        UUID.randomUUID(),
        cents,
        expiresAt);
  }

  @Test
  void settlesValidOrderBetweenParticipants() {
    SettlementOrder o = order("00000001", "00000002", 1500, NOW.plusSeconds(10));

    Settlement s = Settlement.decide(o, PARTICIPANTS, NOW);

    assertThat(s.isSettled()).isTrue();
    assertThat(s.status()).isEqualTo(SettlementStatus.SETTLED);
    assertThat(s.reason()).isNull();
    assertThat(s.decidedAt()).isEqualTo(NOW);
    assertThat(s.transferId()).isEqualTo(o.transferId());
    assertThat(s.amountCents()).isEqualTo(1500);
    assertThat(s.debtorIspb()).isEqualTo("00000001");
    assertThat(s.creditorIspb()).isEqualTo("00000002");
    assertThat(s.creditorAccount()).isEqualTo(o.creditorAccount());
  }

  @Test
  void settlesExactlyAtExpiryButNeverAfter() {
    assertThat(
            Settlement.decide(order("00000001", "00000002", 1, NOW), PARTICIPANTS, NOW).isSettled())
        .isTrue();

    Settlement late =
        Settlement.decide(order("00000001", "00000002", 1, NOW.minusMillis(1)), PARTICIPANTS, NOW);
    assertThat(late.status()).isEqualTo(SettlementStatus.REJECTED);
    assertThat(late.reason()).isEqualTo("expired");
  }

  @ParameterizedTest
  @CsvSource({
    "00000001, 99999999, 100, unknown_creditor_institution",
    "99999999, 00000002, 100, unknown_debtor_institution",
    "00000001, 00000001, 100, same_institution",
    "00000001, 00000002, 0,   invalid_amount",
    "00000001, 00000002, -5,  invalid_amount"
  })
  void rejectsInvalidOrders(String debtor, String creditor, long cents, String reason) {
    Settlement s =
        Settlement.decide(order(debtor, creditor, cents, NOW.plusSeconds(10)), PARTICIPANTS, NOW);

    assertThat(s.isSettled()).isFalse();
    assertThat(s.reason()).isEqualTo(reason);
  }

  @Test
  void requiresMandatoryFields() {
    assertThatThrownBy(() -> order("00000001", "00000002", 1, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                new Settlement(
                    null, "a", "b", UUID.randomUUID(), 1, SettlementStatus.SETTLED, null, NOW))
        .isInstanceOf(NullPointerException.class);
  }
}
