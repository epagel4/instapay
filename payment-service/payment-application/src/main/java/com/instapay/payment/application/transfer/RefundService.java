package com.instapay.payment.application.transfer;

import com.instapay.payment.application.ConflictRetry;
import com.instapay.payment.application.ResourceNotFoundException;
import com.instapay.payment.application.port.out.AccountLocking;
import com.instapay.payment.application.port.out.AccountRepository;
import com.instapay.payment.application.port.out.AuditLog;
import com.instapay.payment.application.port.out.LedgerRepository;
import com.instapay.payment.application.port.out.RefundRepository;
import com.instapay.payment.application.port.out.TransactionRunner;
import com.instapay.payment.application.port.out.TransferRepository;
import com.instapay.payment.domain.account.Account;
import com.instapay.payment.domain.ledger.JournalEntry;
import com.instapay.payment.domain.ledger.JournalEntryId;
import com.instapay.payment.domain.ledger.JournalEntryType;
import com.instapay.payment.domain.shared.DomainException;
import com.instapay.payment.domain.shared.Money;
import com.instapay.payment.domain.transfer.Refund;
import com.instapay.payment.domain.transfer.RefundEvent;
import com.instapay.payment.domain.transfer.RefundId;
import com.instapay.payment.domain.transfer.Transfer;
import com.instapay.payment.domain.transfer.TransferId;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Refunds (devolução): the original creditor returns all or part of a transfer to its debtor.
 *
 * <p>Locks: the original transfer row (serializes refunds of the same transfer, so their sum can
 * never exceed the original), then the creditor account like any debit. Refunds are returns, not
 * new payments, so they do not consume the creditor's transfer limits.
 */
public class RefundService {

  public record RefundSummary(Transfer transfer, List<Refund> refunds, Money totalRefunded) {

    public Money refundable() {
      return transfer.amount().minus(totalRefunded);
    }
  }

  private final TransferRepository transfers;
  private final RefundRepository refunds;
  private final AccountRepository accounts;
  private final AccountLocking locking;
  private final LedgerRepository ledger;
  private final AuditLog audit;
  private final TransactionRunner tx;
  private final Clock clock;
  private final int maxAttempts;

  public RefundService(
      TransferRepository transfers,
      RefundRepository refunds,
      AccountRepository accounts,
      AccountLocking locking,
      LedgerRepository ledger,
      AuditLog audit,
      TransactionRunner tx,
      Clock clock,
      int maxAttempts) {
    this.transfers = transfers;
    this.refunds = refunds;
    this.accounts = accounts;
    this.locking = locking;
    this.ledger = ledger;
    this.audit = audit;
    this.tx = tx;
    this.clock = clock;
    this.maxAttempts = maxAttempts;
  }

  public Refund refund(TransferId original, Money amount, String reason) {
    return ConflictRetry.run(
        maxAttempts, () -> tx.inTransaction(() -> attempt(original, amount, reason)));
  }

  public RefundSummary summary(TransferId original) {
    Transfer transfer = transfers.findById(original).orElseThrow(() -> transferNotFound(original));
    return new RefundSummary(
        transfer, refunds.listByTransfer(original), refunds.totalFor(original));
  }

  private Refund attempt(TransferId originalId, Money amount, String reason) {
    Transfer original =
        transfers.findByIdForUpdate(originalId).orElseThrow(() -> transferNotFound(originalId));
    Money alreadyRefunded = refunds.totalFor(originalId);
    Instant now = clock.instant();
    Refund refund = original.refund(RefundId.newId(), amount, alreadyRefunded, reason, now);

    Account payer = locking.loadForDebit(original.creditor().account()).orElseThrow();
    Account payee = accounts.findById(original.debtor().account()).orElseThrow();
    payer.ensureCanDebit();
    payee.ensureCanCredit();
    if (ledger.balanceOf(payer.id()).compareTo(amount) < 0) {
      throw new DomainException(
          "refund.insufficient_funds", "Creditor has insufficient funds for the refund");
    }

    refunds.insert(refund);
    ledger.append(
        JournalEntry.move(
            JournalEntryId.newId(),
            JournalEntryType.REFUND,
            refund.id().value(),
            payer.id(),
            payee.id(),
            amount,
            now));
    audit.append(RefundEvent.of(refund, alreadyRefunded.plus(amount).cents()));
    locking.confirmDebit(payer);
    return refund;
  }

  private static ResourceNotFoundException transferNotFound(TransferId id) {
    return new ResourceNotFoundException("transfer.not_found", "Transfer " + id + " not found");
  }
}
