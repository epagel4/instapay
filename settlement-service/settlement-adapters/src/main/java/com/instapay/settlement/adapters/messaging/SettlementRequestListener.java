package com.instapay.settlement.adapters.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instapay.contracts.SettlementRequest;
import com.instapay.contracts.SettlementResult;
import com.instapay.contracts.Topics;
import com.instapay.settlement.application.SettleService;
import com.instapay.settlement.domain.Settlement;
import com.instapay.settlement.domain.SettlementOrder;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Decision persisted first, reply published after. If publishing fails the record is redelivered,
 * the stored decision is found and re-published: at-least-once replies without an outbox.
 */
@Component
class SettlementRequestListener {

  private static final Logger log = LoggerFactory.getLogger(SettlementRequestListener.class);

  private final SettleService settle;
  private final KafkaTemplate<String, String> kafka;
  private final ObjectMapper json;
  private final MeterRegistry meters;

  SettlementRequestListener(
      SettleService settle,
      KafkaTemplate<String, String> kafka,
      ObjectMapper json,
      MeterRegistry meters) {
    this.settle = settle;
    this.kafka = kafka;
    this.json = json;
    this.meters = meters;
  }

  @KafkaListener(topics = Topics.SETTLEMENT_REQUESTS, groupId = "settlement-service")
  void onRequest(String payload) throws Exception {
    SettlementRequest r = json.readValue(payload, SettlementRequest.class);
    Settlement s =
        settle.settle(
            new SettlementOrder(
                r.transferId(),
                r.debtorIspb(),
                r.debtorAccount(),
                r.creditorIspb(),
                r.creditorAccount(),
                r.amountCents(),
                r.expiresAt()));
    SettlementResult result =
        new SettlementResult(
            s.transferId(),
            s.isSettled() ? SettlementResult.Status.SETTLED : SettlementResult.Status.REJECTED,
            s.reason(),
            s.decidedAt());
    kafka
        .send(Topics.SETTLEMENT_RESULTS, s.transferId().toString(), json.writeValueAsString(result))
        .get(10, TimeUnit.SECONDS);
    meters
        .counter("instapay.settlement.decisions", "status", s.status().name().toLowerCase())
        .increment();
    log.info(
        "Transfer {} {}{}",
        s.transferId(),
        s.status(),
        s.reason() == null ? "" : " (" + s.reason() + ")");
  }
}
