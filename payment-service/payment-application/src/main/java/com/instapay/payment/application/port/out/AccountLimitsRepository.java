package com.instapay.payment.application.port.out;

import com.instapay.payment.domain.account.AccountId;
import com.instapay.payment.domain.limits.TransferLimits;
import java.util.Optional;

public interface AccountLimitsRepository {

  /** Custom limits of the account; empty means the institution defaults apply. */
  Optional<TransferLimits> find(AccountId account);

  void save(AccountId account, TransferLimits limits);
}
