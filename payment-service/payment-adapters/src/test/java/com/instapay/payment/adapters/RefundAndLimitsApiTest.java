package com.instapay.payment.adapters;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** Refunds and limits; the clock is set to explicit Sao Paulo local times. */
@Import(MutableClock.Config.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "instapay.settlement.timeout-job-enabled=false")
class RefundAndLimitsApiTest extends IntegrationTest {

  static final ZoneId BRT = ZoneId.of("America/Sao_Paulo");

  @Autowired TestRestTemplate http;
  @Autowired JdbcTemplate jdbc;
  @Autowired MutableClock clock;

  Api api() {
    return new Api(http);
  }

  void at(String localDateTime) {
    clock.set(LocalDateTime.parse(localDateTime).atZone(BRT).toInstant());
  }

  UUID funded(String amount) {
    UUID account = api().openAccount();
    api().deposit(account, amount);
    return account;
  }

  UUID transfer(UUID from, UUID to, String amount) {
    ResponseEntity<String> r = api().transfer(Api.key(), from, to, amount);
    assertThat(r.getStatusCode()).as(r.getBody()).isEqualTo(HttpStatus.CREATED);
    return UUID.fromString(JsonPath.read(r.getBody(), "$.id"));
  }

  ResponseEntity<String> refund(UUID transferId, String amount) {
    return api()
        .post(
            "/api/v1/transfers/" + transferId + "/refunds",
            Api.key(),
            "{\"amount\":\"" + amount + "\",\"reason\":\"devolução\"}");
  }

  ResponseEntity<String> putLimits(UUID account, String perTx, String daily, String nightly) {
    return api()
        .send(
            HttpMethod.PUT,
            "/api/v1/accounts/" + account + "/limits",
            Api.key(),
            "{\"perTransaction\":\"%s\",\"daily\":\"%s\",\"nightly\":\"%s\"}"
                .formatted(perTx, daily, nightly));
  }

  String limits(UUID account) {
    return http.getForObject("/api/v1/accounts/" + account + "/limits", String.class);
  }

  // ---------- refunds ----------

  @Test
  void partialRefundsUpToTheOriginalAmount() {
    at("2026-11-10T10:00:00");
    UUID alice = funded("200.00"), bob = api().openAccount();
    UUID t = transfer(alice, bob, "100.00");

    ResponseEntity<String> first = refund(t, "30.00");
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat((String) JsonPath.read(first.getBody(), "$.originalTransferId"))
        .isEqualTo(t.toString());
    assertThat(refund(t, "70.00").getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertProblem(refund(t, "0.01"), HttpStatus.UNPROCESSABLE_ENTITY, "refund.exceeds_original");

    String summary = http.getForObject("/api/v1/transfers/" + t + "/refunds", String.class);
    assertThat((String) JsonPath.read(summary, "$.totalRefunded")).isEqualTo("100.00");
    assertThat((String) JsonPath.read(summary, "$.refundable")).isEqualTo("0.00");
    assertThat((List<?>) JsonPath.read(summary, "$.refunds")).hasSize(2);
    assertThat(api().balance(alice)).isEqualTo("200.00");
    assertThat(api().balance(bob)).isEqualTo("0.00");
    assertThat(
            jdbc.queryForList(
                "SELECT event_type FROM audit_event WHERE aggregate_id = ? ORDER BY id",
                String.class,
                t))
        .containsExactly("TransferCompleted", "TransferRefunded", "TransferRefunded");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM journal_entry j JOIN refund r ON r.id = j.reference WHERE r.original_transfer_id = ? AND j.type = 'REFUND'",
                Long.class,
                t))
        .isEqualTo(2);
  }

  @Test
  void concurrentPartialRefundsNeverExceedTheOriginal() throws Exception {
    at("2026-11-10T11:00:00");
    UUID alice = funded("100.00"), bob = api().openAccount();
    UUID t = transfer(alice, bob, "100.00");
    CountDownLatch start = new CountDownLatch(1);
    List<Integer> statuses;
    try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
      List<Future<ResponseEntity<String>>> futures =
          IntStream.range(0, 10)
              .mapToObj(
                  i ->
                      pool.submit(
                          () -> {
                            start.await();
                            return refund(t, "20.00");
                          }))
              .toList();
      start.countDown();
      statuses =
          futures.stream().map(f -> TransferApiTest.join(f).getStatusCode().value()).toList();
    }

    assertThat(statuses).filteredOn(s -> s == 201).hasSize(5);
    assertThat(statuses).filteredOn(s -> s == 422).hasSize(5);
    assertThat(api().balance(alice)).isEqualTo("100.00");
    assertThat(api().balance(bob)).isEqualTo("0.00");
  }

  @Test
  void refundRules() {
    at("2026-11-10T12:00:00");
    UUID alice = funded("100.00"), bob = api().openAccount(), carol = api().openAccount();
    UUID t = transfer(alice, bob, "50.00");
    transfer(bob, carol, "40.00"); // bob now holds only 10.00

    assertProblem(refund(t, "20.00"), HttpStatus.UNPROCESSABLE_ENTITY, "refund.insufficient_funds");
    assertProblem(refund(UUID.randomUUID(), "1.00"), HttpStatus.NOT_FOUND, "transfer.not_found");
    assertProblem(refund(t, "-1"), HttpStatus.UNPROCESSABLE_ENTITY, "refund.amount.not_positive");

    UUID external =
        UUID.fromString(
            JsonPath.read(
                api()
                    .post(
                        "/api/v1/transfers",
                        Api.key(),
                        "{\"debtorAccountId\":\"%s\",\"creditor\":{\"ispb\":\"00000002\",\"accountId\":\"%s\"},\"amount\":\"1.00\"}"
                            .formatted(alice, UUID.randomUUID()))
                    .getBody(),
                "$.id"));
    assertProblem(
        refund(external, "1.00"), HttpStatus.UNPROCESSABLE_ENTITY, "refund.external_not_supported");

    clock.advance(Duration.ofDays(91));
    assertProblem(refund(t, "1.00"), HttpStatus.UNPROCESSABLE_ENTITY, "refund.window_expired");
  }

  @Test
  void refundsDoNotConsumeTheCreditorsLimits() {
    at("2026-11-10T13:00:00");
    UUID alice = funded("100.00"), bob = api().openAccount();
    putLimits(bob, "10.00", "10.00", "10.00");
    UUID t = transfer(alice, bob, "100.00");

    assertThat(refund(t, "60.00").getStatusCode()).isEqualTo(HttpStatus.CREATED);
  }

  // ---------- limits ----------

  @Test
  void defaultLimitsApplyAndAreVisible() {
    at("2026-11-11T10:00:00");
    UUID alice = funded("6000.00"), bob = api().openAccount();

    assertProblem(
        api().transfer(Api.key(), alice, bob, "5000.01"),
        HttpStatus.UNPROCESSABLE_ENTITY,
        "limit.per_transaction_exceeded");
    transfer(alice, bob, "1200.00");

    String usage = limits(alice);
    assertThat((String) JsonPath.read(usage, "$.perTransaction")).isEqualTo("5000.00");
    assertThat((String) JsonPath.read(usage, "$.daily")).isEqualTo("10000.00");
    assertThat((String) JsonPath.read(usage, "$.nightly")).isEqualTo("1000.00");
    assertThat((Boolean) JsonPath.read(usage, "$.custom")).isFalse();
    assertThat((String) JsonPath.read(usage, "$.spentToday")).isEqualTo("1200.00");
    assertThat((Object) JsonPath.read(usage, "$.spentTonight")).isNull();
  }

  @Test
  void customDailyLimit() {
    at("2026-11-11T09:00:00");
    UUID alice = funded("1000.00"), bob = api().openAccount();
    ResponseEntity<String> updated = putLimits(alice, "100.00", "150.00", "50.00");
    assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat((Boolean) JsonPath.read(updated.getBody(), "$.custom")).isTrue();

    transfer(alice, bob, "100.00");
    transfer(alice, bob, "50.00");
    assertProblem(
        api().transfer(Api.key(), alice, bob, "0.01"),
        HttpStatus.UNPROCESSABLE_ENTITY,
        "limit.daily_exceeded");

    at("2026-11-12T09:00:00"); // next day
    transfer(alice, bob, "100.00");
  }

  @Test
  void nightlyLimitSpansMidnightWhileDailyResets() {
    at("2026-11-12T21:00:00");
    UUID alice = funded("1000.00"), bob = api().openAccount();
    putLimits(alice, "100.00", "300.00", "80.00");

    transfer(alice, bob, "50.00");
    at("2026-11-12T23:30:00");
    transfer(alice, bob, "30.00");
    assertProblem(
        api().transfer(Api.key(), alice, bob, "0.01"),
        HttpStatus.UNPROCESSABLE_ENTITY,
        "limit.nightly_exceeded");

    at("2026-11-13T00:30:00"); // new calendar day, same night
    assertProblem(
        api().transfer(Api.key(), alice, bob, "0.01"),
        HttpStatus.UNPROCESSABLE_ENTITY,
        "limit.nightly_exceeded");
    String usage = limits(alice);
    assertThat((String) JsonPath.read(usage, "$.spentToday")).isEqualTo("0.00");
    assertThat((String) JsonPath.read(usage, "$.spentTonight")).isEqualTo("80.00");

    at("2026-11-13T06:00:00"); // daytime: only daily (0 spent today) applies
    transfer(alice, bob, "100.00");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM transfer WHERE debtor_account = ?", Long.class, alice))
        .isEqualTo(3);
  }

  @Test
  void invalidLimitsAreRejected() {
    UUID alice = api().openAccount();

    assertProblem(
        putLimits(alice, "100.00", "50.00", "10.00"), HttpStatus.BAD_REQUEST, "limits.invalid");
    assertProblem(
        putLimits(alice, "10.00", "50.00", "0"), HttpStatus.BAD_REQUEST, "limits.invalid");
    assertProblem(
        putLimits(UUID.randomUUID(), "10.00", "50.00", "10.00"),
        HttpStatus.NOT_FOUND,
        "account.not_found");
  }

  private static void assertProblem(
      ResponseEntity<String> response, HttpStatus status, String code) {
    assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(status);
    assertThat(response.getHeaders().getContentType())
        .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat((String) JsonPath.read(response.getBody(), "$.code")).isEqualTo(code);
  }
}
