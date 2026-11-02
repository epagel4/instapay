package com.instapay.payment.adapters.config;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/** Keeps traces about money movement: drops Prometheus scrapes and scheduler polls. */
@Configuration(proxyBeanMethods = false)
class ObservabilityConfiguration {

  @Bean
  ObservationPredicate ignoreActuatorAndSchedulerPolls() {
    return (name, context) ->
        !name.equals("tasks.scheduled.execution")
            && !(context instanceof ServerRequestObservationContext request
                && request.getCarrier().getRequestURI().startsWith("/actuator"));
  }
}
