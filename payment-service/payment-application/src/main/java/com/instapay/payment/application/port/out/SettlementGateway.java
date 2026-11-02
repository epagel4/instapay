package com.instapay.payment.application.port.out;

import com.instapay.payment.domain.transfer.Transfer;
import java.time.Instant;

/** Asks the clearing house to settle. Must be atomic with the caller's transaction (outbox). */
public interface SettlementGateway {

  void requestSettlement(Transfer transfer, Instant expiresAt);
}
