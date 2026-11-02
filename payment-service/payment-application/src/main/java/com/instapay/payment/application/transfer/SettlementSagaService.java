package com.instapay.payment.application.transfer;

import com.instapay.payment.application.port.out.AuditLog;
import com.instapay.payment.application.port.out.LedgerRepository;
import com.instapay.payment.application.port.out.SettlementSagaRepository;
import com.instapay.payment.application.port.out.TransactionRunner;
import com.instapay.payment.application.port.out.TransferRepository;
import com.instapay.payment.domain.account.AccountId;
import com.instapay.payment.domain.ledger.JournalEntry;
import com.instapay.payment.domain.ledger.JournalEntryId;
import com.instapay.payment.domain.ledger.JournalEntryType;
import com.instapay.payment.domain.ledger.SystemAccounts;
import com.instapay.payment.domain.transfer.SagaState;
import com.instapay.payment.domain.transfer.SettlementSaga;
import com.instapay.payment.domain.transfer.Transfer;
import com.instapay.payment.domain.transfer.TransferEvent;
import com.instapay.payment.domain.transfer.TransferId;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Drives settlement sagas to an end: settled, rejected (compensate) or overdue (compensate).
 * Idempotent: a result for a saga that already finished changes nothing.
 */
public class SettlementSagaService {

  public enum Outcome {
    COMPLETED,
    COMPENSATED,
    /** Result for a saga that already finished the same way, or a repeated rejection. */
    DUPLICATE,
    /**
     * Settled after we compensated on timeout. Must not happen if the clearing house honours
     * expiresAt; if it does, money left twice and needs manual reconciliation.
     */
    LATE_SETTLEMENT,
    UNKNOWN_TRANSFER
  }

  private final SettlementSagaRepository sagas;
  private final TransferRepository transfers;
  private final LedgerRepository ledger;
  private final AuditLog audit;
  private final TransactionRunner tx;
  private final Clock clock;

  public SettlementSagaService(
      SettlementSagaRepository sagas,
      TransferRepository transfers,
      LedgerRepository ledger,
      AuditLog audit,
      TransactionRunner tx,
      Clock clock) {
    this.sagas = sagas;
    this.transfers = transfers;
    this.ledger = ledger;
    this.audit = audit;
    this.tx = tx;
    this.clock = clock;
  }

  public Outcome onSettlementResult(TransferId id, boolean settled, String reason) {
    return tx.inTransaction(
        () -> {
          Optional<SettlementSaga> found = sagas.findByIdForUpdate(id);
          if (found.isEmpty()) {
            return Outcome.UNKNOWN_TRANSFER;
          }
          SettlementSaga saga = found.get();
          if (!saga.isAwaiting()) {
            return settled && saga.state() == SagaState.COMPENSATED
                ? Outcome.LATE_SETTLEMENT
                : Outcome.DUPLICATE;
          }
          Transfer transfer = transfers.findById(id).orElseThrow();
          Instant now = clock.instant();
          if (settled) {
            saga.settled(now);
            transfer.complete(now);
            post(
                JournalEntryType.SETTLEMENT,
                transfer,
                SystemAccounts.SETTLEMENT_IN_TRANSIT,
                SystemAccounts.SETTLEMENT,
                now);
            audit.append(TransferEvent.of(TransferEvent.Type.COMPLETED, transfer, null, now));
          } else {
            saga.rejected(reason, now);
            compensate(transfer, reason, now);
          }
          sagas.update(saga);
          transfers.update(transfer);
          return settled ? Outcome.COMPLETED : Outcome.COMPENSATED;
        });
  }

  /** Compensates sagas whose settlement can no longer arrive. Returns how many. */
  public int compensateOverdue(int limit) {
    return tx.inTransaction(
        () -> {
          Instant now = clock.instant();
          List<SettlementSaga> overdue = sagas.lockOverdue(now, limit);
          for (SettlementSaga saga : overdue) {
            saga.timedOut(now);
            Transfer transfer = transfers.findById(saga.transferId()).orElseThrow();
            compensate(transfer, SettlementSaga.TIMEOUT_REASON, now);
            sagas.update(saga);
            transfers.update(transfer);
          }
          return overdue.size();
        });
  }

  /** Returns the held amount to the debtor. Credits need no lock: they only increase balance. */
  private void compensate(Transfer transfer, String reason, Instant now) {
    transfer.fail(reason, now);
    post(
        JournalEntryType.COMPENSATION,
        transfer,
        SystemAccounts.SETTLEMENT_IN_TRANSIT,
        transfer.debtor().account(),
        now);
    audit.append(TransferEvent.of(TransferEvent.Type.FAILED, transfer, reason, now));
  }

  private void post(JournalEntryType type, Transfer t, AccountId from, AccountId to, Instant now) {
    ledger.append(
        JournalEntry.move(JournalEntryId.newId(), type, t.id().value(), from, to, t.amount(), now));
  }
}
