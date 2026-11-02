package com.instapay.payment.application.port.out;

import com.instapay.payment.domain.account.AccountId;
import com.instapay.payment.domain.shared.Money;
import com.instapay.payment.domain.transfer.Transfer;
import com.instapay.payment.domain.transfer.TransferId;
import java.time.Instant;
import java.util.Optional;

public interface TransferRepository {

  void insert(Transfer transfer);

  void update(Transfer transfer);

  Optional<Transfer> findById(TransferId id);

  /** Same as findById, holding a row lock until commit. */
  Optional<Transfer> findByIdForUpdate(TransferId id);

  /** Sum of the account's outgoing transfers created in [from, to), excluding failed ones. */
  Money sumOutgoing(AccountId debtor, Instant from, Instant to);
}
