package com.instapay.payment.domain.pixkey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.instapay.payment.domain.account.Account;
import com.instapay.payment.domain.account.AccountHolder;
import com.instapay.payment.domain.account.AccountId;
import com.instapay.payment.domain.shared.Cpf;
import com.instapay.payment.domain.shared.Ispb;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class KeyClaimTest {

  static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
  static final Ispb BANK = new Ispb("00000001");
  static final Cpf MARIA = Cpf.of("52998224725");
  static final Cpf JOAO = Cpf.of("11144477735");

  final Account mariaMain = account(MARIA);
  final Account mariaOther = account(MARIA);
  final Account joao = account(JOAO);
  final PixKeyRegistration phone =
      PixKeyRegistration.register(PixKey.of(PixKeyType.PHONE, "+5511987654321"), mariaMain, 0, NOW);

  static Account account(Cpf cpf) {
    return Account.open(AccountId.newId(), BANK, new AccountHolder("Holder", cpf));
  }

  KeyClaim claim(PixKeyRegistration reg, Account claimer) {
    return KeyClaim.open(KeyClaimId.newId(), reg, mariaMain, claimer, 0, NOW);
  }

  @Test
  void sameHolderIsPortabilityConfirmedByDonor() {
    KeyClaim claim = claim(phone, mariaOther);

    assertThat(claim.type()).isEqualTo(KeyClaimType.PORTABILITY);
    assertThat(claim.status()).isEqualTo(KeyClaimStatus.OPEN);
    assertThat(claim.donor()).isEqualTo(mariaMain.id());
    assertThat(claim.claimer()).isEqualTo(mariaOther.id());
    assertThat(claim.key()).isEqualTo(phone.key());
    assertThat(claim.openedAt()).isEqualTo(NOW);
    assertThat(claim.resolutionDeadline()).isEqualTo(NOW.plus(KeyClaim.RESOLUTION_PERIOD));
    assertThat(claim.resolvedAt()).isEmpty();

    assertThatThrownBy(() -> claim.confirm(mariaOther.id(), NOW))
        .extracting("code")
        .isEqualTo("keyclaim.not_allowed");
    assertThatThrownBy(() -> claim.complete(mariaOther.id(), NOW.plus(KeyClaim.RESOLUTION_PERIOD)))
        .extracting("code")
        .isEqualTo("keyclaim.requires_donor_confirmation");

    claim.confirm(mariaMain.id(), NOW.plusSeconds(5));
    assertThat(claim.status()).isEqualTo(KeyClaimStatus.COMPLETED);
    assertThat(claim.resolvedAt()).contains(NOW.plusSeconds(5));
  }

  @Test
  void differentHolderIsOwnershipCompletedAfterResolutionPeriod() {
    KeyClaim claim = claim(phone, joao);
    Instant deadline = NOW.plus(KeyClaim.RESOLUTION_PERIOD);

    assertThat(claim.type()).isEqualTo(KeyClaimType.OWNERSHIP);
    assertThatThrownBy(() -> claim.complete(joao.id(), deadline.minusMillis(1)))
        .extracting("code")
        .isEqualTo("keyclaim.resolution_period_running");
    assertThatThrownBy(() -> claim.complete(mariaMain.id(), deadline))
        .extracting("code")
        .isEqualTo("keyclaim.not_allowed");

    claim.complete(joao.id(), deadline);
    assertThat(claim.status()).isEqualTo(KeyClaimStatus.COMPLETED);
  }

  @Test
  void donorCanGiveUpOwnershipEarly() {
    KeyClaim claim = claim(phone, joao);
    claim.confirm(mariaMain.id(), NOW);

    assertThat(claim.status()).isEqualTo(KeyClaimStatus.COMPLETED);
  }

  @Test
  void eitherSideCancelsButOutsidersCannot() {
    KeyClaim byDonor = claim(phone, joao);
    byDonor.cancel(mariaMain.id(), NOW);
    assertThat(byDonor.status()).isEqualTo(KeyClaimStatus.CANCELLED);

    KeyClaim byClaimer = claim(phone, joao);
    byClaimer.cancel(joao.id(), NOW);
    assertThat(byClaimer.status()).isEqualTo(KeyClaimStatus.CANCELLED);

    assertThatThrownBy(() -> claim(phone, joao).cancel(AccountId.newId(), NOW))
        .extracting("code")
        .isEqualTo("keyclaim.not_participant");
  }

  @Test
  void resolvedClaimsAreFinal() {
    KeyClaim claim = claim(phone, joao);
    claim.cancel(joao.id(), NOW);

    assertThatThrownBy(() -> claim.confirm(mariaMain.id(), NOW))
        .extracting("code")
        .isEqualTo("keyclaim.not_open");
    assertThatThrownBy(() -> claim.cancel(joao.id(), NOW))
        .extracting("code")
        .isEqualTo("keyclaim.not_open");
    assertThatThrownBy(() -> claim.complete(joao.id(), NOW.plus(KeyClaim.RESOLUTION_PERIOD)))
        .extracting("code")
        .isEqualTo("keyclaim.not_open");
  }

  @ParameterizedTest
  @EnumSource(
      value = PixKeyType.class,
      names = {"CPF", "RANDOM"})
  void cpfAndRandomKeysCannotBeClaimedByAnotherHolder(PixKeyType type) {
    PixKey key = type == PixKeyType.CPF ? PixKey.of(type, MARIA.value()) : PixKey.random();
    PixKeyRegistration reg = PixKeyRegistration.register(key, mariaMain, 0, NOW);

    assertThatThrownBy(() -> claim(reg, joao))
        .extracting("code")
        .isEqualTo("keyclaim.ownership_not_allowed");
    assertThat(claim(reg, mariaOther).type()).isEqualTo(KeyClaimType.PORTABILITY);
  }

  @Test
  void openingValidatesParticipants() {
    assertThatThrownBy(() -> claim(phone, mariaMain))
        .extracting("code")
        .isEqualTo("keyclaim.already_owner");
    assertThatThrownBy(() -> KeyClaim.open(KeyClaimId.newId(), phone, mariaMain, joao, 5, NOW))
        .extracting("code")
        .isEqualTo("pixkey.limit_reached");
    assertThatThrownBy(() -> KeyClaim.open(KeyClaimId.newId(), phone, joao, mariaOther, 0, NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void restoreKeepsStateAndEqualityIsById() {
    KeyClaim original = claim(phone, joao);
    KeyClaim restored =
        KeyClaim.restore(
            original.id(),
            phone.key(),
            KeyClaimType.OWNERSHIP,
            mariaMain.id(),
            joao.id(),
            NOW,
            original.resolutionDeadline(),
            KeyClaimStatus.CANCELLED,
            NOW);

    assertThat(restored.status()).isEqualTo(KeyClaimStatus.CANCELLED);
    assertThat(restored).isEqualTo(original).hasSameHashCodeAs(original);
    assertThat(restored).isNotEqualTo(claim(phone, joao));
    assertThat(restored.id().toString()).isEqualTo(original.id().value().toString());
  }
}
