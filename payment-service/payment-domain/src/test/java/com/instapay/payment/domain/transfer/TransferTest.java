package com.instapay.payment.domain.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.instapay.payment.domain.account.AccountId;
import com.instapay.payment.domain.account.AccountRef;
import com.instapay.payment.domain.shared.Ispb;
import com.instapay.payment.domain.shared.Money;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class TransferTest {

  static final Ispb INSTAPAY = new Ispb("00000001");
  static final Ispb OTHER_BANK = new Ispb("00000002");
  static final Instant T0 = Instant.parse("2026-11-01T12:00:00Z");
  static final Instant T1 = T0.plusMillis(150);

  final AccountRef alice = new AccountRef(INSTAPAY, AccountId.newId());
  final AccountRef bob = new AccountRef(INSTAPAY, AccountId.newId());

  Transfer pending() {
    return Transfer.request(TransferId.newId(), alice, bob, Money.of("25.00"), T0);
  }

  @Test
  void startsPending() {
    Transfer transfer = pending();

    assertThat(transfer.status()).isEqualTo(TransferStatus.PENDING);
    assertThat(transfer.debtor()).isEqualTo(alice);
    assertThat(transfer.creditor()).isEqualTo(bob);
    assertThat(transfer.amount()).isEqualTo(Money.ofCents(2500));
    assertThat(transfer.createdAt()).isEqualTo(T0);
    assertThat(transfer.finishedAt()).isEmpty();
    assertThat(transfer.failureReason()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-0.01"})
  void amountMustBePositive(String amount) {
    assertThatThrownBy(() -> Transfer.request(TransferId.newId(), alice, bob, Money.of(amount), T0))
        .extracting("code")
        .isEqualTo("transfer.amount.not_positive");
  }

  @Test
  void cannotTransferToSameAccount() {
    assertThatThrownBy(() -> Transfer.request(TransferId.newId(), alice, alice, Money.of("1"), T0))
        .extracting("code")
        .isEqualTo("transfer.same_account");
  }

  @Test
  void completesOnce() {
    Transfer transfer = pending();
    transfer.complete(T1);

    assertThat(transfer.status()).isEqualTo(TransferStatus.COMPLETED);
    assertThat(transfer.finishedAt()).contains(T1);
    assertThatThrownBy(() -> transfer.complete(T1))
        .extracting("code")
        .isEqualTo("transfer.transition.invalid");
    assertThatThrownBy(() -> transfer.fail("late", T1))
        .extracting("code")
        .isEqualTo("transfer.transition.invalid");
  }

  @Test
  void failsWithReason() {
    Transfer transfer = pending();
    transfer.fail("insufficient funds", T1);

    assertThat(transfer.status()).isEqualTo(TransferStatus.FAILED);
    assertThat(transfer.failureReason()).contains("insufficient funds");
    assertThat(transfer.finishedAt()).contains(T1);
    assertThatThrownBy(() -> transfer.complete(T1))
        .extracting("code")
        .isEqualTo("transfer.transition.invalid");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"  "})
  void failureNeedsReason(String reason) {
    assertThatThrownBy(() -> pending().fail(reason, T1))
        .extracting("code")
        .isEqualTo("transfer.failure_reason.required");
  }

  @Test
  void internalOnlyWhenBothAccountsInSameInstitution() {
    assertThat(pending().isInternal()).isTrue();

    AccountRef external = new AccountRef(OTHER_BANK, AccountId.newId());
    assertThat(
            Transfer.request(TransferId.newId(), alice, external, Money.of("1"), T0).isInternal())
        .isFalse();
  }

  @Test
  void restoreKeepsStateAndEqualityIsById() {
    Transfer original = pending();
    Transfer restored =
        Transfer.restore(
            original.id(), alice, bob, original.amount(), T0, TransferStatus.FAILED, T1, "timeout");

    assertThat(restored.status()).isEqualTo(TransferStatus.FAILED);
    assertThat(restored.failureReason()).contains("timeout");
    assertThat(restored).isEqualTo(original).hasSameHashCodeAs(original);
    assertThat(restored).isNotEqualTo(pending());
    assertThat(restored.id().toString()).isEqualTo(original.id().value().toString());
  }
}
