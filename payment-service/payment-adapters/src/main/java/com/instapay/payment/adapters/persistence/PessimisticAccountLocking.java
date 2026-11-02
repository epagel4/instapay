package com.instapay.payment.adapters.persistence;

import com.instapay.payment.application.port.out.AccountLocking;
import com.instapay.payment.domain.account.Account;
import com.instapay.payment.domain.account.AccountId;
import java.util.Optional;

/** SELECT ... FOR UPDATE: concurrent debits of the same account queue on the row lock. */
public class PessimisticAccountLocking implements AccountLocking {

  private final JdbcAccountRepository accounts;

  public PessimisticAccountLocking(JdbcAccountRepository accounts) {
    this.accounts = accounts;
  }

  @Override
  public Optional<Account> loadForDebit(AccountId id) {
    return accounts.findByIdForUpdate(id);
  }

  @Override
  public void confirmDebit(Account account) {
    // Lock is held until commit; nothing to verify.
  }
}
