package com.instapay.payment.domain.transfer;

import com.instapay.payment.domain.shared.DomainException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Orchestration state of a transfer to another institution (ADR-005).
 *
 * <p>The clearing house must not settle after {@code settlementExpiresAt}; we only compensate on
 * timeout after {@code compensateAfter} (expiry + grace), so a settlement decided in time but
 * delivered late is still received before we give up.
 */
public final class SettlementSaga {

  public static final String TIMEOUT_REASON = "settlement.timeout";

  private final TransferId transferId;
  private final Instant startedAt;
  private final Instant settlementExpiresAt;
  private final Instant compensateAfter;
  private SagaState state;
  private String reason;
  private Instant finishedAt;

  private SettlementSaga(
      TransferId transferId,
      Instant startedAt,
      Instant settlementExpiresAt,
      Instant compensateAfter,
      SagaState state,
      String reason,
      Instant finishedAt) {
    this.transferId = Objects.requireNonNull(transferId, "transferId");
    this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
    this.settlementExpiresAt = Objects.requireNonNull(settlementExpiresAt, "settlementExpiresAt");
    this.compensateAfter = Objects.requireNonNull(compensateAfter, "compensateAfter");
    this.state = Objects.requireNonNull(state, "state");
    this.reason = reason;
    this.finishedAt = finishedAt;
  }

  public static SettlementSaga start(
      TransferId transferId, Instant now, Duration settlementTimeout, Duration grace) {
    if (settlementTimeout.isNegative() || settlementTimeout.isZero() || grace.isNegative()) {
      throw new IllegalArgumentException("Timeout must be positive and grace non-negative");
    }
    Instant expiresAt = now.plus(settlementTimeout);
    return new SettlementSaga(
        transferId,
        now,
        expiresAt,
        expiresAt.plus(grace),
        SagaState.AWAITING_SETTLEMENT,
        null,
        null);
  }

  /** Rehydrates persisted state; no transition rules applied. */
  public static SettlementSaga restore(
      TransferId transferId,
      Instant startedAt,
      Instant settlementExpiresAt,
      Instant compensateAfter,
      SagaState state,
      String reason,
      Instant finishedAt) {
    return new SettlementSaga(
        transferId, startedAt, settlementExpiresAt, compensateAfter, state, reason, finishedAt);
  }

  public void settled(Instant now) {
    requireAwaiting();
    finish(SagaState.COMPLETED, null, now);
  }

  public void rejected(String rejectionReason, Instant now) {
    requireAwaiting();
    if (rejectionReason == null || rejectionReason.isBlank()) {
      throw new DomainException("saga.reason.required", "Rejection reason is required");
    }
    finish(SagaState.COMPENSATED, rejectionReason, now);
  }

  public void timedOut(Instant now) {
    requireAwaiting();
    if (now.isBefore(compensateAfter)) {
      throw new DomainException(
          "saga.not_overdue", "Settlement can still arrive until " + compensateAfter);
    }
    finish(SagaState.COMPENSATED, TIMEOUT_REASON, now);
  }

  public boolean isAwaiting() {
    return state == SagaState.AWAITING_SETTLEMENT;
  }

  private void requireAwaiting() {
    if (!isAwaiting()) {
      throw new DomainException(
          "saga.already_finished", "Saga " + transferId + " is already " + state);
    }
  }

  private void finish(SagaState newState, String newReason, Instant now) {
    state = newState;
    reason = newReason;
    finishedAt = Objects.requireNonNull(now, "now");
  }

  public TransferId transferId() {
    return transferId;
  }

  public Instant startedAt() {
    return startedAt;
  }

  public Instant settlementExpiresAt() {
    return settlementExpiresAt;
  }

  public Instant compensateAfter() {
    return compensateAfter;
  }

  public SagaState state() {
    return state;
  }

  public Optional<String> reason() {
    return Optional.ofNullable(reason);
  }

  public Optional<Instant> finishedAt() {
    return Optional.ofNullable(finishedAt);
  }
}
