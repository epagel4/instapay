package com.instapay.settlement.application;

import com.instapay.settlement.application.port.out.SettlementRepository;
import com.instapay.settlement.domain.Settlement;
import com.instapay.settlement.domain.SettlementOrder;
import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Idempotent: the first decision for a transfer is final and returned for every redelivery. */
public class SettleService {

  private final SettlementRepository settlements;
  private final Set<String> participants;
  private final Clock clock;

  public SettleService(SettlementRepository settlements, Set<String> participants, Clock clock) {
    this.settlements = settlements;
    this.participants = Set.copyOf(participants);
    this.clock = clock;
  }

  public Settlement settle(SettlementOrder order) {
    return settlements
        .findByTransferId(order.transferId())
        .orElseGet(
            () ->
                settlements.insertIfAbsent(
                    Settlement.decide(order, participants, clock.instant())));
  }

  public Optional<Settlement> find(UUID transferId) {
    return settlements.findByTransferId(transferId);
  }
}
