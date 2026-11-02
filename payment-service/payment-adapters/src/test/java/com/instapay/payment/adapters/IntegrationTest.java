package com.instapay.payment.adapters;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Base for Spring integration tests: one Postgres, Redis and Kafka for every test context in the
 * JVM. They are deliberately not Spring beans: Boot stops container beans when the first context
 * closes, leaving the other cached contexts to time out against dead brokers at shutdown. Ryuk
 * removes them when the JVM exits. Tests isolate data with fresh accounts, never by cleanup.
 */
abstract class IntegrationTest {

  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
  static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

  static {
    POSTGRES.start();
    REDIS.start();
    KAFKA.start();
  }

  @DynamicPropertySource
  static void containerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    // Several cached contexts share one Postgres (max_connections 100): keep each pool small.
    registry.add("spring.datasource.hikari.maximum-pool-size", () -> 10);
  }
}
