package com.instapay.payment.adapters;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
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

@Import(MutableClock.Config.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "instapay.settlement.timeout-job-enabled=false")
class PixKeyApiTest extends IntegrationTest {

  static final String MARIA = "52998224725";
  static final String JOAO = "11144477735";

  @Autowired TestRestTemplate http;
  @Autowired MutableClock clock;

  Api api() {
    return new Api(http);
  }

  ResponseEntity<String> register(UUID account, String type, String value) {
    String valueJson = value == null ? "" : ",\"value\":\"" + value + "\"";
    return api()
        .post(
            "/api/v1/keys",
            Api.key(),
            "{\"accountId\":\"%s\",\"type\":\"%s\"%s}".formatted(account, type, valueJson));
  }

  ResponseEntity<String> lookup(String type, String value) {
    return http.getForEntity("/api/v1/keys/" + type + "/" + value, String.class);
  }

  ResponseEntity<String> openClaim(UUID claimer, String type, String value) {
    return api()
        .post(
            "/api/v1/key-claims",
            Api.key(),
            "{\"claimerAccountId\":\"%s\",\"keyType\":\"%s\",\"keyValue\":\"%s\"}"
                .formatted(claimer, type, value));
  }

  ResponseEntity<String> claimAction(String claimId, String action, UUID actor) {
    return api()
        .post(
            "/api/v1/key-claims/" + claimId + "/" + action,
            Api.key(),
            "{\"accountId\":\"" + actor + "\"}");
  }

  static String unique(String prefix) {
    return prefix + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
  }

  static String uniquePhone() {
    return "+55119" + String.format("%08d", (long) (Math.random() * 100_000_000));
  }

  static String json(ResponseEntity<String> r, String path) {
    return JsonPath.read(r.getBody(), path);
  }

  // ---------- registration & lookup ----------

  @Test
  void registersEveryKeyTypeInCanonicalFormAndLooksThemUp() {
    UUID account = api().openAccount(MARIA);
    String email = unique("Maria.");

    assertThat(register(account, "EMAIL", "  " + email.toUpperCase() + " ").getStatusCode())
        .isEqualTo(HttpStatus.CREATED);
    ResponseEntity<String> random = register(account, "RANDOM", null);
    assertThat(random.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(json(random, "$.value")).matches("[0-9a-f-]{36}");
    assertThat(random.getHeaders().getLocation())
        .hasToString("/api/v1/keys/RANDOM/" + json(random, "$.value"));

    ResponseEntity<String> found = lookup("EMAIL", email.toLowerCase());
    assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(json(found, "$.accountId")).isEqualTo(account.toString());
    assertThat(json(found, "$.institution")).isEqualTo("00000001");
    assertThat(json(found, "$.holderCpf")).isEqualTo("***.982.247-**");
    assertThat(found.getBody()).doesNotContain(MARIA);

    List<String> listed =
        JsonPath.read(
            http.getForObject("/api/v1/accounts/" + account + "/keys", String.class), "$[*].type");
    assertThat(listed).containsExactly("EMAIL", "RANDOM");
  }

  @Test
  void keyIsUniqueAcrossAccounts() {
    UUID first = api().openAccount(MARIA), second = api().openAccount(JOAO);
    String phone = uniquePhone();

    assertThat(register(first, "PHONE", phone).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertProblem(
        register(second, "PHONE", phone),
        HttpStatus.UNPROCESSABLE_ENTITY,
        "pixkey.already_registered");
    assertProblem(
        register(first, "PHONE", phone),
        HttpStatus.UNPROCESSABLE_ENTITY,
        "pixkey.already_registered");
  }

  @Test
  void concurrentRegistrationOfSameKeyHasExactlyOneWinner() throws Exception {
    String email = unique("race.");
    List<UUID> accounts = IntStream.range(0, 10).mapToObj(i -> api().openAccount(JOAO)).toList();
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService pool = Executors.newFixedThreadPool(accounts.size())) {
      List<Future<ResponseEntity<String>>> futures =
          accounts.stream()
              .map(
                  a ->
                      pool.submit(
                          () -> {
                            start.await();
                            return register(a, "EMAIL", email);
                          }))
              .toList();
      start.countDown();
      List<Integer> statuses =
          futures.stream().map(f -> TransferApiTest.join(f).getStatusCode().value()).toList();

      assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
      assertThat(statuses).filteredOn(s -> s == 422).hasSize(9);
    }
  }

  @Test
  void registrationRules() {
    UUID account = api().openAccount(MARIA);

    assertThat(register(account, "CPF", "529.982.247-25").getStatusCode())
        .isEqualTo(HttpStatus.CREATED);
    assertProblem(
        register(api().openAccount(MARIA), "CPF", JOAO),
        HttpStatus.UNPROCESSABLE_ENTITY,
        "pixkey.cpf_mismatch");
    assertProblem(
        register(account, "PHONE", "11987654321"), HttpStatus.BAD_REQUEST, "pixkey.invalid");
    assertProblem(register(account, "EMAIL", null), HttpStatus.BAD_REQUEST, "pixkey.invalid");
    assertProblem(
        register(account, "RANDOM", UUID.randomUUID().toString()),
        HttpStatus.UNPROCESSABLE_ENTITY,
        "pixkey.random_value_not_allowed");
    assertProblem(
        register(UUID.randomUUID(), "RANDOM", null), HttpStatus.NOT_FOUND, "account.not_found");
    assertProblem(lookup("EMAIL", unique("nobody.")), HttpStatus.NOT_FOUND, "pixkey.not_found");
    assertThat(lookup("BOGUS", "x").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

    for (int i = 0; i < 4; i++) {
      assertThat(register(account, "RANDOM", null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
    assertProblem(
        register(account, "RANDOM", null), HttpStatus.UNPROCESSABLE_ENTITY, "pixkey.limit_reached");
  }

  // ---------- deletion ----------

  @Test
  void onlyOwnerDeletesAndDeletionIsIdempotent() {
    UUID owner = api().openAccount(MARIA), other = api().openAccount(JOAO);
    String email = unique("del.");
    register(owner, "EMAIL", email);
    String path = "/api/v1/keys/EMAIL/" + email + "?accountId=";

    assertProblem(
        api().send(HttpMethod.DELETE, path + other, Api.key(), null),
        HttpStatus.UNPROCESSABLE_ENTITY,
        "pixkey.not_owner");

    String key = Api.key();
    assertThat(api().send(HttpMethod.DELETE, path + owner, key, null).getStatusCode())
        .isEqualTo(HttpStatus.NO_CONTENT);
    ResponseEntity<String> replay = api().send(HttpMethod.DELETE, path + owner, key, null);
    assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(replay.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
    assertProblem(lookup("EMAIL", email), HttpStatus.NOT_FOUND, "pixkey.not_found");
    assertThat(register(other, "EMAIL", email).getStatusCode())
        .isEqualTo(HttpStatus.CREATED); // freed
  }

  // ---------- claims ----------

  @Test
  void portabilityBetweenAccountsOfSameHolder() {
    UUID donor = api().openAccount(MARIA), claimer = api().openAccount(MARIA);
    String phone = uniquePhone();
    register(donor, "PHONE", phone);

    ResponseEntity<String> opened = openClaim(claimer, "PHONE", phone);
    assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(json(opened, "$.type")).isEqualTo("PORTABILITY");
    assertThat(json(opened, "$.status")).isEqualTo("OPEN");
    String claimId = json(opened, "$.id");

    assertProblem(
        openClaim(api().openAccount(MARIA), "PHONE", phone),
        HttpStatus.UNPROCESSABLE_ENTITY,
        "keyclaim.already_open");
    assertProblem(
        api()
            .send(
                HttpMethod.DELETE,
                "/api/v1/keys/PHONE/" + phone + "?accountId=" + donor,
                Api.key(),
                null),
        HttpStatus.UNPROCESSABLE_ENTITY,
        "pixkey.claim_open");
    assertProblem(
        claimAction(claimId, "complete", claimer),
        HttpStatus.UNPROCESSABLE_ENTITY,
        "keyclaim.requires_donor_confirmation");
    assertProblem(
        claimAction(claimId, "confirm", claimer),
        HttpStatus.UNPROCESSABLE_ENTITY,
        "keyclaim.not_allowed");

    ResponseEntity<String> confirmed = claimAction(claimId, "confirm", donor);
    assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(json(confirmed, "$.status")).isEqualTo("COMPLETED");
    assertThat(json(lookup("PHONE", phone), "$.accountId")).isEqualTo(claimer.toString());
    assertProblem(
        claimAction(claimId, "cancel", donor),
        HttpStatus.UNPROCESSABLE_ENTITY,
        "keyclaim.not_open");
  }

  @Test
  void ownershipClaimCompletesOnlyAfterResolutionPeriod() {
    UUID donor = api().openAccount(MARIA), claimer = api().openAccount(JOAO);
    String email = unique("owner.");
    register(donor, "EMAIL", email);

    ResponseEntity<String> opened = openClaim(claimer, "EMAIL", email);
    assertThat(json(opened, "$.type")).isEqualTo("OWNERSHIP");
    String claimId = json(opened, "$.id");

    clock.advance(Duration.ofDays(6));
    assertProblem(
        claimAction(claimId, "complete", claimer),
        HttpStatus.UNPROCESSABLE_ENTITY,
        "keyclaim.resolution_period_running");
    assertThat(json(lookup("EMAIL", email), "$.accountId")).isEqualTo(donor.toString());

    clock.advance(Duration.ofDays(1));
    ResponseEntity<String> completed = claimAction(claimId, "complete", claimer);
    assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(json(completed, "$.status")).isEqualTo("COMPLETED");
    assertThat(json(lookup("EMAIL", email), "$.accountId")).isEqualTo(claimer.toString());
    assertThat(json(lookup("EMAIL", email), "$.holderCpf")).isEqualTo("***.444.777-**");
  }

  @Test
  void donorContestingOwnershipKeepsTheKey() {
    UUID donor = api().openAccount(MARIA), claimer = api().openAccount(JOAO);
    String email = unique("contest.");
    register(donor, "EMAIL", email);
    String claimId = json(openClaim(claimer, "EMAIL", email), "$.id");

    ResponseEntity<String> cancelled = claimAction(claimId, "cancel", donor);

    assertThat(json(cancelled, "$.status")).isEqualTo("CANCELLED");
    assertThat(json(lookup("EMAIL", email), "$.accountId")).isEqualTo(donor.toString());
    assertThat(http.getForObject("/api/v1/key-claims/" + claimId, String.class))
        .contains("CANCELLED");
    assertThat(openClaim(claimer, "EMAIL", email).getStatusCode())
        .isEqualTo(HttpStatus.CREATED); // may retry
  }

  @Test
  void claimRules() {
    UUID donor = api().openAccount(MARIA), stranger = api().openAccount(JOAO);
    register(donor, "CPF", MARIA);
    String random = json(register(donor, "RANDOM", null), "$.value");

    assertProblem(
        openClaim(stranger, "CPF", MARIA),
        HttpStatus.UNPROCESSABLE_ENTITY,
        "keyclaim.ownership_not_allowed");
    assertProblem(
        openClaim(stranger, "RANDOM", random),
        HttpStatus.UNPROCESSABLE_ENTITY,
        "keyclaim.ownership_not_allowed");
    assertProblem(
        openClaim(donor, "RANDOM", random),
        HttpStatus.UNPROCESSABLE_ENTITY,
        "keyclaim.already_owner");
    assertProblem(
        openClaim(stranger, "EMAIL", unique("ghost.")), HttpStatus.NOT_FOUND, "pixkey.not_found");
    assertThat(
            http.getForEntity("/api/v1/key-claims/" + UUID.randomUUID(), String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  private static void assertProblem(
      ResponseEntity<String> response, HttpStatus status, String code) {
    assertThat(response.getStatusCode()).isEqualTo(status);
    assertThat(response.getHeaders().getContentType())
        .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(json(response, "$.code")).isEqualTo(code);
  }
}
