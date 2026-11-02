package com.instapay.settlement.application.port.out;

import com.instapay.settlement.domain.Settlement;
import java.util.Optional;
import java.util.UUID;

public interface SettlementRepository {

  Optional<Settlement> findByTransferId(UUID transferId);

  /** Stores the decision unless one exists for the transfer; returns whichever is stored. */
  Settlement insertIfAbsent(Settlement settlement);
}
