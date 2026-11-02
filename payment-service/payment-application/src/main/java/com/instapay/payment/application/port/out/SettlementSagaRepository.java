package com.instapay.payment.application.port.out;

import com.instapay.payment.domain.transfer.SettlementSaga;
import com.instapay.payment.domain.transfer.TransferId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SettlementSagaRepository {

  void insert(SettlementSaga saga);

  Optional<SettlementSaga> findByIdForUpdate(TransferId id);

  /** Awaiting sagas past their compensation deadline, locked; rows locked elsewhere are skipped. */
  List<SettlementSaga> lockOverdue(Instant now, int limit);

  void update(SettlementSaga saga);
}
