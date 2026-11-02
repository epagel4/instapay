package com.instapay.payment.adapters;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Test clock that only moves when told to; replaces the system clock bean. */
final class MutableClock extends Clock {

  private volatile Instant now;

  MutableClock(Instant start) {
    this.now = start;
  }

  void advance(Duration duration) {
    now = now.plus(duration);
  }

  void set(Instant instant) {
    now = instant;
  }

  @Override
  public Instant instant() {
    return now;
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return this;
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Config {

    @Bean
    @Primary
    MutableClock mutableClock() {
      return new MutableClock(Instant.parse("2026-11-02T12:00:00Z"));
    }
  }
}
