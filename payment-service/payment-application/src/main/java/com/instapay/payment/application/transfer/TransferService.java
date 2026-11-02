package com.instapay.payment.application.transfer;

import com.instapay.payment.application.ConflictRetry;
import com.instapay.payment.application.ResourceNotFoundException;
import com.instapay.payment.application.limits.LimitService;
import com.instapay.payment.application.port.out.AccountLocking;
import com.instapay.payment.application.port.out.AccountRepository;
import com.instapay.payment.application.port.out.AuditLog;
import com.instapay.payment.application.port.out.LedgerRepository;
import com.instapay.payment.application.port.out.SettlementGateway;
import com.instapay.payment.application.port.out.SettlementSagaRepository;
import com.instapay.payment.application.port.out.TransactionRunner;
import com.instapay.payment.application.port.out.TransferRepository;
import com.instapay.payment.domain.account.Account;
import com.instapay.payment.domain.account.AccountId;
import com.instapay.payment.domain.account.AccountRef;
import com.instapay.payment.domain.ledger.JournalEntry;
import com.instapay.payment.domain.ledger.JournalEntryId;
import com.instapay.payment.domain.ledger.JournalEntryType;
import com.instapay.payment.domain.ledger.SystemAccounts;
import com.instapay.payment.domain.shared.DomainException;
import com.instapay.payment.domain.shared.Ispb;
import com.instapay.payment.domain.shared.Money;
import com.instapay.payment.domain.transfer.SettlementSaga;
import com.instapay.payment.domain.transfer.Transfer;
import com.instapay.payment.domain.transfer.TransferEvent;
import com.instapay.payment.domain.transfer.TransferId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Starts transfers. Internal (creditor in this institution): settled synchronously in one commit.
 * External: funds held in transit and a settlement saga started, in one commit (ADR-005/006).
 */
public class TransferService {

  public record TransferCommand(AccountId debtor, AccountRef creditor, Money amount) {}

  public record Settings(
      Ispb institution, int maxAttempts, Duration settlementTimeout, Duration compensationGrace) {}

  private final AccountRepository accounts;
  private final AccountLocking locking;
  private final LedgerRepository ledger;
  private final TransferRepository transfers;
  private final SettlementSagaRepository sagas;
  private final SettlementGateway settlement;
  private final AuditLog audit;
  private final LimitService limits;
  private final TransactionRunner tx;
  private final Clock clock;
  private final Settings settings;

  public TransferService(
      AccountRepository accounts,
      AccountLocking locking,
      LedgerRepository ledger,
      TransferRepository transfers,
      SettlementSagaRepository sagas,
      SettlementGateway settlement,
      AuditLog audit,
      LimitService limits,
      TransactionRunner tx,
      Clock clock,
      Settings settings) {
    this.accounts = accounts;
    this.locking = locking;
    this.ledger = ledger;
    this.transfers = transfers;
    this.sagas = sagas;
    this.settlement = settlement;
    this.audit = audit;
    this.limits = limits;
    this.tx = tx;
    this.clock = clock;
    this.settings = settings;
  }

  /** Retries only optimistic-lock conflicts; business rule violations fail immediately. */
  public Transfer transfer(TransferCommand command) {
    return ConflictRetry.run(
        settings.maxAttempts(), () -> tx.inTransaction(() -> attempt(command)));
  }

  public Transfer get(TransferId id) {
    return transfers
        .findById(id)
        .orElseThrow(
            () ->
                new ResourceNotFoundException(
                    "transfer.not_found", "Transfer " + id + " not found"));
  }

  private Transfer attempt(TransferCommand command) {
    Account debtor =
        locking.loadForDebit(command.debtor()).orElseThrow(() -> accountNotFound(command.debtor()));
    boolean internal = command.creditor().institution().equals(settings.institution());
    if (internal) {
      accounts
          .findById(command.creditor().account())
          .orElseThrow(() -> accountNotFound(command.creditor().account()))
          .ensureCanCredit();
    }
    Instant now = clock.instant();
    Transfer transfer =
        Transfer.request(
            TransferId.newId(), debtor.ref(), command.creditor(), command.amount(), now);
    debtor.ensureCanDebit();
    limits.check(debtor.id(), command.amount(), now);

    // Safe only because loadForDebit serialized (or will detect) concurrent debits.
    if (ledger.balanceOf(debtor.id()).compareTo(command.amount()) < 0) {
      throw new DomainException("transfer.insufficient_funds", "Insufficient funds");
    }

    if (internal) {
      transfer.complete(now);
      transfers.insert(transfer);
      post(JournalEntryType.TRANSFER, transfer, debtor.id(), transfer.creditor().account(), now);
      audit.append(TransferEvent.of(TransferEvent.Type.COMPLETED, transfer, null, now));
    } else {
      transfers.insert(transfer);
      post(
          JournalEntryType.EXTERNAL_TRANSFER,
          transfer,
          debtor.id(),
          SystemAccounts.SETTLEMENT_IN_TRANSIT,
          now);
      SettlementSaga saga =
          SettlementSaga.start(
              transfer.id(), now, settings.settlementTimeout(), settings.compensationGrace());
      sagas.insert(saga);
      settlement.requestSettlement(transfer, saga.settlementExpiresAt());
      audit.append(TransferEvent.of(TransferEvent.Type.SETTLEMENT_REQUESTED, transfer, null, now));
    }
    locking.confirmDebit(debtor);
    return transfer;
  }

  private void post(JournalEntryType type, Transfer t, AccountId from, AccountId to, Instant now) {
    ledger.append(
        JournalEntry.move(JournalEntryId.newId(), type, t.id().value(), from, to, t.amount(), now));
  }

  private static ResourceNotFoundException accountNotFound(AccountId id) {
    return new ResourceNotFoundException("account.not_found", "Account " + id + " not found");
  }
}
