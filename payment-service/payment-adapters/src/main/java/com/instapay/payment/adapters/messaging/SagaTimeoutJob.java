package com.instapay.payment.adapters.messaging;

import com.instapay.payment.application.transfer.SettlementSagaService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Compensates sagas whose settlement can no longer arrive (e.g. settlement-service down). Disabled
 * in tests that move the clock forward: it would compensate other tests' sagas.
 */
@Component
@ConditionalOnProperty(
    name = "instapay.settlement.timeout-job-enabled",
    havingValue = "true",
    matchIfMissing = true)
class SagaTimeoutJob {

  private static final Logger log = LoggerFactory.getLogger(SagaTimeoutJob.class);

  private final SettlementSagaService sagas;
  private final MeterRegistry meters;

  SagaTimeoutJob(SettlementSagaService sagas, MeterRegistry meters) {
    this.sagas = sagas;
    this.meters = meters;
  }

  @Scheduled(fixedDelayString = "${instapay.settlement.timeout-check-interval-ms}")
  void compensateOverdue() {
    int compensated = sagas.compensateOverdue(100);
    if (compensated > 0) {
      meters.counter("instapay.saga.results", "outcome", "timeout").increment(compensated);
      log.warn("Compensated {} settlement saga(s) after timeout", compensated);
    }
  }
}
