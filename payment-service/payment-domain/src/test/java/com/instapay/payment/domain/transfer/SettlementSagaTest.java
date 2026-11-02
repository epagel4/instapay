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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class SettlementSagaTest {

  static final Instant T0 = Instant.parse("2026-11-03T10:00:00Z");
  static final Duration TIMEOUT = Duration.ofSeconds(10);
  static final Duration GRACE = Duration.ofSeconds(5);

  final SettlementSaga saga = SettlementSaga.start(TransferId.newId(), T0, TIMEOUT, GRACE);

  @Test
  void startsAwaitingWithExpiryAndCompensationDeadline() {
    assertThat(saga.state()).isEqualTo(SagaState.AWAITING_SETTLEMENT);
    assertThat(saga.isAwaiting()).isTrue();
    assertThat(saga.startedAt()).isEqualTo(T0);
    assertThat(saga.settlementExpiresAt()).isEqualTo(T0.plusSeconds(10));
    assertThat(saga.compensateAfter()).isEqualTo(T0.plusSeconds(15));
    assertThat(saga.reason()).isEmpty();
    assertThat(saga.finishedAt()).isEmpty();
  }

  @Test
  void settledCompletes() {
    saga.settled(T0.plusSeconds(1));

    assertThat(saga.state()).isEqualTo(SagaState.COMPLETED);
    assertThat(saga.finishedAt()).contains(T0.plusSeconds(1));
    assertThat(saga.isAwaiting()).isFalse();
  }

  @Test
  void rejectionCompensatesWithReason() {
    saga.rejected("unknown_institution", T0.plusSeconds(1));

    assertThat(saga.state()).isEqualTo(SagaState.COMPENSATED);
    assertThat(saga.reason()).contains("unknown_institution");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = " ")
  void rejectionNeedsReason(String reason) {
    assertThatThrownBy(() -> saga.rejected(reason, T0))
        .extracting("code")
        .isEqualTo("saga.reason.required");
  }

  @Test
  void timeoutOnlyAfterExpiryPlusGrace() {
    assertThatThrownBy(() -> saga.timedOut(T0.plusSeconds(15).minusMillis(1)))
        .extracting("code")
        .isEqualTo("saga.not_overdue");

    saga.timedOut(T0.plusSeconds(15));

    assertThat(saga.state()).isEqualTo(SagaState.COMPENSATED);
    assertThat(saga.reason()).contains(SettlementSaga.TIMEOUT_REASON);
  }

  @Test
  void finishedSagaAcceptsNoMoreTransitions() {
    saga.settled(T0);

    assertThatThrownBy(() -> saga.settled(T0))
        .extracting("code")
        .isEqualTo("saga.already_finished");
    assertThatThrownBy(() -> saga.rejected("x", T0))
        .extracting("code")
        .isEqualTo("saga.already_finished");
    assertThatThrownBy(() -> saga.timedOut(T0.plusSeconds(60)))
        .extracting("code")
        .isEqualTo("saga.already_finished");
  }

  @Test
  void rejectsInvalidDurations() {
    assertThatThrownBy(() -> SettlementSaga.start(TransferId.newId(), T0, Duration.ZERO, GRACE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> SettlementSaga.start(TransferId.newId(), T0, TIMEOUT, Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void restoreKeepsState() {
    SettlementSaga restored =
        SettlementSaga.restore(
            saga.transferId(),
            T0,
            T0.plusSeconds(10),
            T0.plusSeconds(15),
            SagaState.COMPENSATED,
            "x",
            T0);

    assertThat(restored.state()).isEqualTo(SagaState.COMPENSATED);
    assertThat(restored.reason()).contains("x");
    assertThat(restored.finishedAt()).contains(T0);
  }

  @Test
  void transferEventSnapshotsTheTransfer() {
    AccountRef debtor = new AccountRef(new Ispb("00000001"), AccountId.newId());
    AccountRef creditor = new AccountRef(new Ispb("00000002"), AccountId.newId());
    Transfer t = Transfer.request(TransferId.newId(), debtor, creditor, Money.of("12.34"), T0);

    TransferEvent requested =
        TransferEvent.of(TransferEvent.Type.SETTLEMENT_REQUESTED, t, null, T0);
    t.fail("settlement.timeout", T0);
    TransferEvent failed = TransferEvent.of(TransferEvent.Type.FAILED, t, "settlement.timeout", T0);

    assertThat(requested.aggregateType()).isEqualTo("Transfer");
    assertThat(requested.aggregateId()).isEqualTo(t.id().value());
    assertThat(requested.eventType()).isEqualTo("TransferSettlementRequested");
    assertThat(requested.status()).isEqualTo(TransferStatus.PENDING);
    assertThat(requested.amountCents()).isEqualTo(1234);
    assertThat(requested.creditorIspb()).isEqualTo("00000002");
    assertThat(failed.eventType()).isEqualTo("TransferFailed");
    assertThat(failed.status()).isEqualTo(TransferStatus.FAILED);
    assertThat(failed.detail()).isEqualTo("settlement.timeout");
    assertThat(TransferEvent.of(TransferEvent.Type.COMPLETED, t, null, T0).eventType())
        .isEqualTo("TransferCompleted");
    assertThat(failed.occurredAt()).isEqualTo(T0);
  }
}
