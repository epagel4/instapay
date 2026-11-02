package com.instapay.payment.application.port.out;

import com.instapay.payment.domain.account.Account;
import com.instapay.payment.domain.account.AccountId;
import java.util.Optional;

/**
 * Guarantees that two debits of the same account never both see the same balance (ADR-002). Must be
 * used inside a transaction: {@link #loadForDebit} first, {@link #confirmDebit} last.
 */
public interface AccountLocking {

  /** Pessimistic: blocks concurrent debits until commit. Optimistic: plain read with version. */
  Optional<Account> loadForDebit(AccountId id);

  /**
   * Pessimistic: no-op. Optimistic: bumps the version, throwing {@link
   * ConcurrencyConflictException} if another debit committed in between.
   */
  void confirmDebit(Account account);
}
