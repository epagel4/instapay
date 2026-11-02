package com.instapay.settlement.adapters.config;

import com.instapay.contracts.Topics;
import com.instapay.settlement.application.SettleService;
import com.instapay.settlement.application.port.out.SettlementRepository;
import java.time.Clock;
import java.util.Set;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration(proxyBeanMethods = false)
class SettlementConfiguration {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  SettleService settleService(
      SettlementRepository settlements,
      Clock clock,
      @Value("${instapay.settlement.participants}") Set<String> participants) {
    return new SettleService(settlements, participants, clock);
  }

  @Bean
  NewTopic settlementRequestsTopic() {
    return TopicBuilder.name(Topics.SETTLEMENT_REQUESTS).partitions(3).replicas(1).build();
  }

  @Bean
  NewTopic settlementResultsTopic() {
    return TopicBuilder.name(Topics.SETTLEMENT_RESULTS).partitions(3).replicas(1).build();
  }
}
