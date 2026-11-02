package com.instapay.payment.application.account;

import com.instapay.payment.application.ResourceNotFoundException;
import com.instapay.payment.application.port.out.AccountRepository;
import com.instapay.payment.application.port.out.LedgerRepository;
import com.instapay.payment.application.port.out.TransactionRunner;
import com.instapay.payment.domain.account.Account;
import com.instapay.payment.domain.account.AccountHolder;
import com.instapay.payment.domain.account.AccountId;
import com.instapay.payment.domain.ledger.JournalEntry;
import com.instapay.payment.domain.ledger.JournalEntryId;
import com.instapay.payment.domain.shared.Cpf;
import com.instapay.payment.domain.shared.Ispb;
import com.instapay.payment.domain.shared.Money;
import java.time.Clock;

public class AccountService {

  public record AccountWithBalance(Account account, Money balance) {}

  public record DepositResult(JournalEntryId journalEntryId, Money balance) {}

  private final AccountRepository accounts;
  private final LedgerRepository ledger;
  private final TransactionRunner tx;
  private final Clock clock;
  private final Ispb institution;

  public AccountService(
      AccountRepository accounts,
      LedgerRepository ledger,
      TransactionRunner tx,
      Clock clock,
      Ispb institution) {
    this.accounts = accounts;
    this.ledger = ledger;
    this.tx = tx;
    this.clock = clock;
    this.institution = institution;
  }

  public Account open(String holderName, String holderCpf) {
    Account account =
        Account.open(
            AccountId.newId(), institution, new AccountHolder(holderName, Cpf.of(holderCpf)));
    return tx.inTransaction(
        () -> {
          accounts.insert(account);
          return account;
        });
  }

  /** Credits never need the debit lock: they can only increase the balance. */
  public DepositResult deposit(AccountId accountId, Money amount) {
    return tx.inTransaction(
        () -> {
          load(accountId).ensureCanCredit();
          JournalEntry entry =
              JournalEntry.deposit(JournalEntryId.newId(), accountId, amount, clock.instant());
          ledger.append(entry);
          return new DepositResult(entry.id(), ledger.balanceOf(accountId));
        });
  }

  public AccountWithBalance get(AccountId accountId) {
    return new AccountWithBalance(load(accountId), ledger.balanceOf(accountId));
  }

  private Account load(AccountId id) {
    return accounts
        .findById(id)
        .orElseThrow(
            () ->
                new ResourceNotFoundException("account.not_found", "Account " + id + " not found"));
  }
}
