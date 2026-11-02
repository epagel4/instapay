package com.instapay.payment.application.port.out;

import com.instapay.payment.domain.account.Account;
import com.instapay.payment.domain.account.AccountId;
import java.util.Optional;

public interface AccountRepository {

  void insert(Account account);

  Optional<Account> findById(AccountId id);

  /** Same as findById, holding a row lock until commit (serializes per-account invariants). */
  Optional<Account> findByIdForUpdate(AccountId id);
}
