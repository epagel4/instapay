package com.instapay.payment.adapters.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instapay.contracts.SettlementResult;
import com.instapay.contracts.Topics;
import com.instapay.payment.application.transfer.SettlementSagaService;
import com.instapay.payment.application.transfer.SettlementSagaService.Outcome;
import com.instapay.payment.domain.transfer.TransferId;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Idempotent consumer: the saga state machine ignores results for finished sagas. */
@Component
class SettlementResultListener {

  private static final Logger log = LoggerFactory.getLogger(SettlementResultListener.class);

  private final SettlementSagaService sagas;
  private final ObjectMapper json;
  private final MeterRegistry meters;

  SettlementResultListener(SettlementSagaService sagas, ObjectMapper json, MeterRegistry meters) {
    this.sagas = sagas;
    this.json = json;
    this.meters = meters;
  }

  @KafkaListener(
      topics = Topics.SETTLEMENT_RESULTS,
      groupId = "${instapay.settlement.results-group:payment-service}")
  void onResult(String payload) throws JsonProcessingException {
    SettlementResult result = json.readValue(payload, SettlementResult.class);
    Outcome outcome =
        sagas.onSettlementResult(
            new TransferId(result.transferId()),
            result.status() == SettlementResult.Status.SETTLED,
            result.reason());
    meters.counter("instapay.saga.results", "outcome", outcome.name().toLowerCase()).increment();
    switch (outcome) {
      case LATE_SETTLEMENT ->
          log.error(
              "Transfer {} settled after timeout compensation: reconciliation required",
              result.transferId());
      case UNKNOWN_TRANSFER ->
          log.warn("Settlement result for unknown transfer {}", result.transferId());
      case DUPLICATE ->
          log.info("Duplicate settlement result for transfer {} ignored", result.transferId());
      default -> log.info("Transfer {} {}", result.transferId(), outcome);
    }
  }
}
