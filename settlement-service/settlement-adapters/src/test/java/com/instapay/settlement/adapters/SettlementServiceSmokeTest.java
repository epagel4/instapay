package com.instapay.settlement.adapters;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

@AutoConfigureObservability
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SettlementServiceSmokeTest extends IntegrationTest {

  @Autowired TestRestTemplate http;

  @Test
  void healthIsUpWithDatabase() {
    var response = http.getForEntity("/actuator/health", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains("\"status\":\"UP\"");
  }

  @Test
  void exposesPrometheusMetricsAndOpenApi() {
    assertThat(http.getForEntity("/actuator/prometheus", String.class).getBody())
        .contains("application=\"settlement-service\"");
    assertThat(http.getForEntity("/v3/api-docs", String.class).getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }
}
