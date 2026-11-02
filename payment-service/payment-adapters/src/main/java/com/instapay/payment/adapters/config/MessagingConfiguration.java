package com.instapay.payment.adapters.config;

import com.instapay.contracts.Topics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
class MessagingConfiguration {

  @Bean
  NewTopic settlementRequestsTopic() {
    return TopicBuilder.name(Topics.SETTLEMENT_REQUESTS).partitions(3).replicas(1).build();
  }

  @Bean
  NewTopic settlementResultsTopic() {
    return TopicBuilder.name(Topics.SETTLEMENT_RESULTS).partitions(3).replicas(1).build();
  }

  /** Saga and outbox backlogs, read at scrape time (partial indexes keep these cheap). */
  @Bean
  Gauge pendingSagasGauge(MeterRegistry meters, JdbcTemplate jdbc) {
    return Gauge.builder(
            "instapay.saga.pending",
            jdbc,
            j ->
                count(
                    j, "SELECT count(*) FROM settlement_saga WHERE state = 'AWAITING_SETTLEMENT'"))
        .description("Settlement sagas awaiting a result")
        .register(meters);
  }

  @Bean
  Gauge pendingOutboxGauge(MeterRegistry meters, JdbcTemplate jdbc) {
    return Gauge.builder(
            "instapay.outbox.pending",
            jdbc,
            j -> count(j, "SELECT count(*) FROM outbox_event WHERE published_at IS NULL"))
        .description("Outbox messages not yet published")
        .register(meters);
  }

  private static double count(JdbcTemplate jdbc, String sql) {
    Long n = jdbc.queryForObject(sql, Long.class);
    return n == null ? 0 : n;
  }
}
