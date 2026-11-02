package com.instapay.payment.domain.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.instapay.payment.domain.account.AccountId;
import com.instapay.payment.domain.account.AccountRef;
import com.instapay.payment.domain.shared.Ispb;
import com.instapay.payment.domain.shared.Money;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RefundTest {

  static final Instant T0 = Instant.parse("2026-11-04T12:00:00Z");
  static final Ispb BANK = new Ispb("00000001");

  final Transfer transfer = completed(new AccountRef(BANK, AccountId.newId()));

  static Transfer completed(AccountRef creditor) {
    Transfer t =
        Transfer.request(
            TransferId.newId(),
            new AccountRef(BANK, AccountId.newId()),
            creditor,
            Money.of("100"),
            T0);
    t.complete(T0);
    return t;
  }

  @Test
  void fullRefund() {
    Refund r =
        transfer.refund(
            RefundId.newId(), Money.of("100"), Money.ZERO, " wrong amount ", T0.plusSeconds(60));

    assertThat(r.originalTransfer()).isEqualTo(transfer.id());
    assertThat(r.amount()).isEqualTo(Money.of("100"));
    assertThat(r.reason()).isEqualTo("wrong amount");
    assertThat(r.createdAt()).isEqualTo(T0.plusSeconds(60));
  }

  @Test
  void partialRefundsUpToTheOriginalAmount() {
    transfer.refund(RefundId.newId(), Money.of("30"), Money.ZERO, null, T0);
    transfer.refund(RefundId.newId(), Money.of("70"), Money.of("30"), null, T0);

    assertThatThrownBy(
            () -> transfer.refund(RefundId.newId(), Money.of("0.01"), Money.of("100"), null, T0))
        .extracting("code")
        .isEqualTo("refund.exceeds_original");
  }

  @Test
  void amountMustBePositive() {
    assertThatThrownBy(() -> transfer.refund(RefundId.newId(), Money.ZERO, Money.ZERO, null, T0))
        .extracting("code")
        .isEqualTo("refund.amount.not_positive");
  }

  @Test
  void onlyCompletedInternalTransfersWithinNinetyDays() {
    Transfer pending =
        Transfer.request(
            TransferId.newId(),
            new AccountRef(BANK, AccountId.newId()),
            new AccountRef(BANK, AccountId.newId()),
            Money.of("1"),
            T0);
    assertThatThrownBy(() -> pending.refund(RefundId.newId(), Money.of("1"), Money.ZERO, null, T0))
        .extracting("code")
        .isEqualTo("refund.transfer_not_completed");

    Transfer external = completed(new AccountRef(new Ispb("00000002"), AccountId.newId()));
    assertThatThrownBy(() -> external.refund(RefundId.newId(), Money.of("1"), Money.ZERO, null, T0))
        .extracting("code")
        .isEqualTo("refund.external_not_supported");

    Instant lastDay = T0.plus(Transfer.REFUND_WINDOW);
    assertThat(transfer.refund(RefundId.newId(), Money.of("1"), Money.ZERO, null, lastDay))
        .isNotNull();
    assertThatThrownBy(
            () ->
                transfer.refund(
                    RefundId.newId(),
                    Money.of("1"),
                    Money.ZERO,
                    null,
                    lastDay.plus(Duration.ofSeconds(1))))
        .extracting("code")
        .isEqualTo("refund.window_expired");
  }

  @Test
  void reasonIsBounded() {
    assertThatThrownBy(
            () -> transfer.refund(RefundId.newId(), Money.of("1"), Money.ZERO, "x".repeat(141), T0))
        .extracting("code")
        .isEqualTo("refund.reason.invalid");
  }

  @Test
  void eventGoesOnTheOriginalTransferTrail() {
    Refund r = transfer.refund(RefundId.newId(), Money.of("40"), Money.of("10"), "duplicate", T0);
    RefundEvent e = RefundEvent.of(r, 5000);

    assertThat(e.aggregateType()).isEqualTo("Transfer");
    assertThat(e.aggregateId()).isEqualTo(transfer.id().value());
    assertThat(e.eventType()).isEqualTo("TransferRefunded");
    assertThat(e.refundId()).isEqualTo(r.id().value());
    assertThat(e.amountCents()).isEqualTo(4000);
    assertThat(e.totalRefundedCents()).isEqualTo(5000);
    assertThat(e.reason()).isEqualTo("duplicate");
    assertThat(e.occurredAt()).isEqualTo(T0);
    assertThat(r.id().toString()).isEqualTo(r.id().value().toString());
  }
}
