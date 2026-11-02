package com.instapay.payment.adapters;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransferApiTest extends IntegrationTest {

  @Autowired TestRestTemplate http;
  @Autowired JdbcTemplate jdbc;
  @Autowired StringRedisTemplate redis;

  Api api() {
    return new Api(http);
  }

  long transfersFrom(UUID account) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM transfer WHERE debtor_account = ?", Long.class, account);
  }

  // ---------- happy path ----------

  @Test
  void fullInternalTransferFlow() {
    UUID alice = api().openAccount(), bob = api().openAccount();
    assertThat(api().deposit(alice, "100.00").getStatusCode()).isEqualTo(HttpStatus.CREATED);

    ResponseEntity<String> created = api().transfer(Api.key(), alice, bob, "30.25");

    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    String id = JsonPath.read(created.getBody(), "$.id");
    assertThat(created.getHeaders().getLocation()).hasToString("/api/v1/transfers/" + id);
    assertThat((String) JsonPath.read(created.getBody(), "$.status")).isEqualTo("COMPLETED");
    assertThat((String) JsonPath.read(created.getBody(), "$.amount")).isEqualTo("30.25");
    assertThat(http.getForObject("/api/v1/transfers/" + id, String.class))
        .contains("\"COMPLETED\"");
    assertThat(api().balance(alice)).isEqualTo("69.75");
    assertThat(api().balance(bob)).isEqualTo("30.25");
  }

  @Test
  void accountResponseMasksCpf() {
    String body = http.getForObject("/api/v1/accounts/" + api().openAccount(), String.class);

    assertThat(body).contains("***.982.247-**").doesNotContain("52998224725");
  }

  // ---------- idempotency ----------

  @Test
  void sameKeyReplaysFirstResponseAndMovesMoneyOnce() {
    UUID alice = api().openAccount(), bob = api().openAccount();
    api().deposit(alice, "100.00");
    String key = Api.key();

    ResponseEntity<String> first = api().transfer(key, alice, bob, "10.00");
    ResponseEntity<String> second = api().transfer(key, alice, bob, "10.00");

    assertThat(second.getStatusCode()).isEqualTo(first.getStatusCode());
    assertThat(second.getBody()).isEqualTo(first.getBody());
    assertThat(first.getHeaders().containsKey("Idempotent-Replayed")).isFalse();
    assertThat(second.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
    assertThat(transfersFrom(alice)).isEqualTo(1);
    assertThat(api().balance(alice)).isEqualTo("90.00");
  }

  @Test
  void replaysFromPostgresWhenCacheIsLostAndRewarmsCache() {
    UUID alice = api().openAccount(), bob = api().openAccount();
    api().deposit(alice, "50.00");
    String key = Api.key();
    ResponseEntity<String> first = api().transfer(key, alice, bob, "5.00");
    assertThat(redis.hasKey("idempotency:" + key)).isTrue();

    redis.delete("idempotency:" + key);
    ResponseEntity<String> replay = api().transfer(key, alice, bob, "5.00");

    assertThat(replay.getBody()).isEqualTo(first.getBody());
    assertThat(replay.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
    assertThat(redis.hasKey("idempotency:" + key)).isTrue();
    assertThat(transfersFrom(alice)).isEqualTo(1);
  }

  @Test
  void concurrentRequestsWithSameKeyExecuteOnce() throws Exception {
    UUID alice = api().openAccount(), bob = api().openAccount();
    api().deposit(alice, "100.00");
    String key = Api.key();
    int threads = 20;
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
      List<Future<ResponseEntity<String>>> futures =
          IntStream.range(0, threads)
              .mapToObj(
                  i ->
                      pool.submit(
                          () -> {
                            start.await();
                            return api().transfer(key, alice, bob, "1.00");
                          }))
              .toList();
      start.countDown();

      List<ResponseEntity<String>> responses = futures.stream().map(TransferApiTest::join).toList();
      assertThat(responses)
          .allSatisfy(r -> assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED));
      assertThat(responses.stream().map(ResponseEntity::getBody).distinct()).hasSize(1);
    }
    assertThat(transfersFrom(alice)).isEqualTo(1);
    assertThat(api().balance(alice)).isEqualTo("99.00");
  }

  @Test
  void reusingKeyWithDifferentPayloadIsRejected() {
    UUID alice = api().openAccount(), bob = api().openAccount();
    api().deposit(alice, "100.00");
    String key = Api.key();
    api().transfer(key, alice, bob, "10.00");

    ResponseEntity<String> reused = api().transfer(key, alice, bob, "99.00");

    assertProblem(reused, HttpStatus.UNPROCESSABLE_ENTITY, "idempotency.key_reused");
    assertThat(transfersFrom(alice)).isEqualTo(1);
  }

  @Test
  void writesWithoutKeyAreRejectedButReadsAreNot() {
    ResponseEntity<String> response =
        api()
            .post(
                "/api/v1/accounts", null, "{\"holderName\":\"Ana\",\"holderCpf\":\"52998224725\"}");

    assertProblem(response, HttpStatus.BAD_REQUEST, "idempotency.key_missing");
    assertThat(
            http.getForEntity("/api/v1/accounts/" + api().openAccount(), String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }

  @Test
  void businessRejectionIsReplayedEvenAfterStateChanges() {
    UUID alice = api().openAccount(), bob = api().openAccount();
    String key = Api.key();
    assertProblem(
        api().transfer(key, alice, bob, "10.00"),
        HttpStatus.UNPROCESSABLE_ENTITY,
        "transfer.insufficient_funds");

    api().deposit(alice, "100.00");

    ResponseEntity<String> replay = api().transfer(key, alice, bob, "10.00");
    assertProblem(replay, HttpStatus.UNPROCESSABLE_ENTITY, "transfer.insufficient_funds");
    assertThat(replay.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
    assertThat(api().transfer(Api.key(), alice, bob, "10.00").getStatusCode())
        .isEqualTo(HttpStatus.CREATED);
  }

  // ---------- RFC 7807 ----------

  @Test
  void errorsAreProblemDetails() {
    UUID alice = api().openAccount(), ghost = UUID.randomUUID();

    assertProblem(
        api().transfer(Api.key(), ghost, alice, "1.00"), HttpStatus.NOT_FOUND, "account.not_found");
    assertProblem(
        api().transfer(Api.key(), alice, alice, "1.00"),
        HttpStatus.UNPROCESSABLE_ENTITY,
        "transfer.same_account");
    assertProblem(
        api().transfer(Api.key(), alice, ghost, "1.234"), HttpStatus.BAD_REQUEST, "money.invalid");
    assertProblem(
        api().deposit(alice, "-5"), HttpStatus.UNPROCESSABLE_ENTITY, "ledger.amount.not_positive");
    assertProblem(
        api()
            .post(
                "/api/v1/accounts",
                Api.key(),
                "{\"holderName\":\"Ana\",\"holderCpf\":\"111.111.111-11\"}"),
        HttpStatus.BAD_REQUEST,
        "cpf.invalid");
    assertThat(
            http.getForEntity("/api/v1/transfers/" + UUID.randomUUID(), String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);

    ResponseEntity<String> malformed = api().post("/api/v1/transfers", Api.key(), "{not json");
    assertThat(malformed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(malformed.getHeaders().getContentType())
        .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(api().post("/api/v1/transfers", Api.key(), "{}").getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void openApiDocumentsIdempotencyKey() {
    assertThat(http.getForObject("/v3/api-docs", String.class))
        .contains("/api/v1/transfers")
        .contains("Idempotency-Key");
  }

  private static void assertProblem(
      ResponseEntity<String> response, HttpStatus status, String code) {
    assertThat(response.getStatusCode()).isEqualTo(status);
    assertThat(response.getHeaders().getContentType())
        .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat((String) JsonPath.read(response.getBody(), "$.code")).isEqualTo(code);
    assertThat((String) JsonPath.read(response.getBody(), "$.type")).endsWith("/problems/" + code);
  }

  static <T> T join(Future<T> future) {
    try {
      return future.get();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
