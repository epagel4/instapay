package com.instapay.payment.adapters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instapay.contracts.SettlementRequest;
import com.instapay.contracts.SettlementResult;
import com.instapay.contracts.Topics;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Saga against real Postgres + Kafka. The clearing house is a stub inside the test: it records the
 * requests the outbox relay publishes, and each test decides what (if anything) to answer.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "instapay.settlement.timeout=1s",
      "instapay.settlement.compensation-grace=1s",
      "instapay.settlement.timeout-check-interval-ms=200",
      "instapay.outbox.poll-interval-ms=100",
      "management.otlp.tracing.export.enabled=false",
      // Own consumer group: other cached contexts share the broker and would steal the results
      // this test counts in its own MeterRegistry.
      "instapay.settlement.results-group=payment-service-saga-test"
    })
@AutoConfigureObservability(metrics = false) // tracing on: the trace must survive the outbox
class ExternalTransferSagaTest extends IntegrationTest {

  static final String OTHER_BANK = "00000002";
  static final Map<UUID, SettlementRequest> REQUESTS = new ConcurrentHashMap<>();
  static final Map<UUID, String> TRACEPARENTS = new ConcurrentHashMap<>();
  static volatile boolean stubRunning = true;
  static Thread stub;

  @Autowired TestRestTemplate http;
  @Autowired JdbcTemplate jdbc;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired ObjectMapper json;
  @Autowired MeterRegistry meters;

  @BeforeAll
  static void startClearingHouseStub() {
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-clearing-house-" + UUID.randomUUID());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    // The topic may not exist yet when the stub subscribes; rediscover it quickly.
    props.put(ConsumerConfig.METADATA_MAX_AGE_CONFIG, "500");
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    stub =
        Thread.ofVirtual()
            .start(
                () -> {
                  try (var consumer =
                      new KafkaConsumer<>(
                          props, new StringDeserializer(), new StringDeserializer())) {
                    consumer.subscribe(List.of(Topics.SETTLEMENT_REQUESTS));
                    while (stubRunning) {
                      for (var record : consumer.poll(Duration.ofMillis(100))) {
                        SettlementRequest r = readRequest(mapper, record.value());
                        Header tp = record.headers().lastHeader("traceparent");
                        if (tp != null) {
                          TRACEPARENTS.put(
                              r.transferId(), new String(tp.value(), StandardCharsets.UTF_8));
                        }
                        REQUESTS.put(r.transferId(), r);
                      }
                    }
                  }
                });
  }

  @AfterAll
  static void stopStub() throws InterruptedException {
    stubRunning = false;
    stub.join(5000);
  }

  static SettlementRequest readRequest(ObjectMapper mapper, String payload) {
    try {
      return mapper.readValue(payload, SettlementRequest.class);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // ---------- helpers ----------

  Api api() {
    return new Api(http);
  }

  ResponseEntity<String> externalTransfer(UUID debtor, String ispb, String amount) {
    return api()
        .post(
            "/api/v1/transfers",
            Api.key(),
            "{\"debtorAccountId\":\"%s\",\"creditor\":{\"ispb\":\"%s\",\"accountId\":\"%s\"},\"amount\":\"%s\"}"
                .formatted(debtor, ispb, UUID.randomUUID(), amount));
  }

  UUID fundedAccount(String amount) {
    UUID account = api().openAccount();
    api().deposit(account, amount);
    return account;
  }

  SettlementRequest awaitRequest(UUID transferId) {
    await().atMost(Duration.ofSeconds(10)).until(() -> REQUESTS.containsKey(transferId));
    return REQUESTS.get(transferId);
  }

  void reply(UUID transferId, SettlementResult.Status status, String reason) throws Exception {
    kafka
        .send(
            Topics.SETTLEMENT_RESULTS,
            transferId.toString(),
            json.writeValueAsString(
                new SettlementResult(transferId, status, reason, Instant.now())))
        .get();
  }

  String transferStatus(UUID id) {
    return JsonPath.read(http.getForObject("/api/v1/transfers/" + id, String.class), "$.status");
  }

  String sagaState(UUID id) {
    return jdbc.queryForObject(
        "SELECT state FROM settlement_saga WHERE transfer_id = ?", String.class, id);
  }

  long systemBalance(int system) {
    return jdbc.queryForObject(
        "SELECT coalesce(sum(amount_cents), 0)::bigint FROM posting p JOIN journal_entry j ON j.id = p.journal_entry_id"
            + " WHERE p.account_id = ? AND j.reference = ANY(SELECT transfer_id FROM settlement_saga)",
        Long.class,
        new UUID(0, system));
  }

  long entries(UUID transferId, String type) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM journal_entry WHERE reference = ? AND type = ?",
        Long.class,
        transferId,
        type);
  }

  List<String> auditTrail(UUID transferId) {
    return jdbc.queryForList(
        "SELECT event_type FROM audit_event WHERE aggregate_id = ? ORDER BY id",
        String.class,
        transferId);
  }

  double counter(String outcome) {
    return Optional.ofNullable(
            meters.find("instapay.saga.results").tag("outcome", outcome).counter())
        .map(Counter::count)
        .orElse(0.0);
  }

  void assertLedgerBalanced() {
    assertThat(
            jdbc.queryForObject(
                "SELECT coalesce(sum(amount_cents), 0)::bigint FROM posting", Long.class))
        .isZero();
  }

  // ---------- happy path ----------

  @Test
  void settledTransferCompletesAndMovesMoneyOutOfTransit() throws Exception {
    UUID alice = fundedAccount("100.00");

    ResponseEntity<String> accepted = externalTransfer(alice, OTHER_BANK, "30.00");
    assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    UUID id = UUID.fromString(JsonPath.read(accepted.getBody(), "$.id"));
    assertThat((String) JsonPath.read(accepted.getBody(), "$.status")).isEqualTo("PENDING");
    assertThat(api().balance(alice)).isEqualTo("70.00"); // held immediately

    SettlementRequest request = awaitRequest(id);
    assertThat(request.amountCents()).isEqualTo(3000);
    assertThat(request.creditorIspb()).isEqualTo(OTHER_BANK);
    assertThat(request.debtorIspb()).isEqualTo("00000001");
    assertThat(request.expiresAt()).isAfter(request.requestedAt());
    assertThat(
            jdbc.queryForObject(
                "SELECT published_at IS NOT NULL FROM outbox_event WHERE message_key = ?",
                Boolean.class,
                id.toString()))
        .isTrue();

    reply(id, SettlementResult.Status.SETTLED, null);

    await().atMost(Duration.ofSeconds(10)).until(() -> transferStatus(id).equals("COMPLETED"));
    assertThat(sagaState(id)).isEqualTo("COMPLETED");
    assertThat(api().balance(alice)).isEqualTo("70.00");
    assertThat(entries(id, "EXTERNAL_TRANSFER")).isEqualTo(1);
    assertThat(entries(id, "SETTLEMENT")).isEqualTo(1);
    assertThat(entries(id, "COMPENSATION")).isZero();
    assertThat(auditTrail(id)).containsExactly("TransferSettlementRequested", "TransferCompleted");
    assertLedgerBalanced();
  }

  @Test
  void outboxPublishContinuesTheTraceOfTheHttpRequest() {
    UUID alice = fundedAccount("100.00");
    String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
    String callerSpan = "00f067aa0ba902b7";
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", Api.key());
    headers.set("traceparent", "00-" + traceId + "-" + callerSpan + "-01");
    String body =
        "{\"debtorAccountId\":\"%s\",\"creditor\":{\"ispb\":\"%s\",\"accountId\":\"%s\"},\"amount\":\"1.00\"}"
            .formatted(alice, OTHER_BANK, UUID.randomUUID());

    ResponseEntity<String> accepted =
        http.exchange(
            "/api/v1/transfers", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    UUID id = UUID.fromString(JsonPath.read(accepted.getBody(), "$.id"));
    awaitRequest(id);

    // Published by the relay thread, yet still the caller's trace, under a new producer span.
    await().atMost(Duration.ofSeconds(5)).until(() -> TRACEPARENTS.containsKey(id));
    String[] published = TRACEPARENTS.get(id).split("-");
    assertThat(published[1]).isEqualTo(traceId);
    assertThat(published[2]).isNotEqualTo(callerSpan);
    assertThat(
            jdbc.queryForObject(
                "SELECT trace_parent FROM outbox_event WHERE message_key = ?",
                String.class,
                id.toString()))
        .startsWith("00-" + traceId + "-");
  }

  // ---------- failures ----------

  @Test
  void rejectionCompensatesTheDebtor() throws Exception {
    UUID alice = fundedAccount("100.00");
    UUID id =
        UUID.fromString(
            JsonPath.read(externalTransfer(alice, OTHER_BANK, "40.00").getBody(), "$.id"));
    awaitRequest(id);

    reply(id, SettlementResult.Status.REJECTED, "unknown_institution");

    await().atMost(Duration.ofSeconds(10)).until(() -> transferStatus(id).equals("FAILED"));
    assertThat(sagaState(id)).isEqualTo("COMPENSATED");
    assertThat(api().balance(alice)).isEqualTo("100.00");
    assertThat(entries(id, "COMPENSATION")).isEqualTo(1);
    assertThat(
            (String)
                JsonPath.read(
                    http.getForObject("/api/v1/transfers/" + id, String.class), "$.failureReason"))
        .isEqualTo("unknown_institution");
    assertThat(auditTrail(id)).containsExactly("TransferSettlementRequested", "TransferFailed");
    assertLedgerBalanced();
  }

  @Test
  void settlementDownCompensatesAfterTimeout() {
    double timeoutsBefore = counter("timeout");
    UUID alice = fundedAccount("100.00");
    UUID id =
        UUID.fromString(
            JsonPath.read(externalTransfer(alice, OTHER_BANK, "25.00").getBody(), "$.id"));
    awaitRequest(id); // published, but nobody answers

    await().atMost(Duration.ofSeconds(10)).until(() -> transferStatus(id).equals("FAILED"));

    assertThat(sagaState(id)).isEqualTo("COMPENSATED");
    assertThat(
            jdbc.queryForObject(
                "SELECT reason FROM settlement_saga WHERE transfer_id = ?", String.class, id))
        .isEqualTo("settlement.timeout");
    assertThat(api().balance(alice)).isEqualTo("100.00");
    assertThat(counter("timeout")).isGreaterThan(timeoutsBefore);
    assertLedgerBalanced();
  }

  @Test
  void duplicateResultIsAppliedOnce() throws Exception {
    UUID alice = fundedAccount("100.00");
    UUID id =
        UUID.fromString(
            JsonPath.read(externalTransfer(alice, OTHER_BANK, "10.00").getBody(), "$.id"));
    awaitRequest(id);
    double duplicatesBefore = counter("duplicate");

    reply(id, SettlementResult.Status.SETTLED, null);
    reply(id, SettlementResult.Status.SETTLED, null);
    reply(id, SettlementResult.Status.REJECTED, "late noise");

    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> counter("duplicate") >= duplicatesBefore + 2);
    assertThat(transferStatus(id)).isEqualTo("COMPLETED");
    assertThat(entries(id, "SETTLEMENT")).isEqualTo(1);
    assertThat(entries(id, "COMPENSATION")).isZero();
    assertThat(api().balance(alice)).isEqualTo("90.00");
    assertLedgerBalanced();
  }

  @Test
  void settlementArrivingAfterCompensationIsFlaggedNotApplied() throws Exception {
    UUID alice = fundedAccount("100.00");
    UUID id =
        UUID.fromString(
            JsonPath.read(externalTransfer(alice, OTHER_BANK, "15.00").getBody(), "$.id"));
    awaitRequest(id);
    await().atMost(Duration.ofSeconds(10)).until(() -> transferStatus(id).equals("FAILED"));
    double lateBefore = counter("late_settlement");

    reply(id, SettlementResult.Status.SETTLED, null);

    await().atMost(Duration.ofSeconds(10)).until(() -> counter("late_settlement") > lateBefore);
    assertThat(transferStatus(id)).isEqualTo("FAILED");
    assertThat(entries(id, "SETTLEMENT")).isZero();
    assertThat(api().balance(alice)).isEqualTo("100.00");
  }

  @Test
  void insufficientFundsStartsNoSagaAndPublishesNothing() {
    UUID alice = fundedAccount("5.00");

    ResponseEntity<String> response = externalTransfer(alice, OTHER_BANK, "5.01");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM settlement_saga s JOIN transfer t ON t.id = s.transfer_id WHERE t.debtor_account = ?",
                Long.class,
                alice))
        .isZero();
  }

  // ---------- creditor resolution ----------

  @Test
  void transferToKeyResolvesToLocalAccount() {
    UUID alice = fundedAccount("50.00"), bob = api().openAccount();
    String email = "bob." + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    api()
        .post(
            "/api/v1/keys",
            Api.key(),
            "{\"accountId\":\"%s\",\"type\":\"EMAIL\",\"value\":\"%s\"}".formatted(bob, email));

    ResponseEntity<String> created =
        api()
            .post(
                "/api/v1/transfers",
                Api.key(),
                "{\"debtorAccountId\":\"%s\",\"creditorKey\":{\"type\":\"EMAIL\",\"value\":\"%s\"},\"amount\":\"20.00\"}"
                    .formatted(alice, email.toUpperCase()));

    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat((String) JsonPath.read(created.getBody(), "$.creditorAccountId"))
        .isEqualTo(bob.toString());
    assertThat(api().balance(bob)).isEqualTo("20.00");
  }

  @Test
  void creditorMustBeGivenExactlyOnce() {
    UUID alice = fundedAccount("10.00");

    ResponseEntity<String> none =
        api()
            .post(
                "/api/v1/transfers",
                Api.key(),
                "{\"debtorAccountId\":\"%s\",\"amount\":\"1.00\"}".formatted(alice));
    ResponseEntity<String> unknownKey =
        api()
            .post(
                "/api/v1/transfers",
                Api.key(),
                "{\"debtorAccountId\":\"%s\",\"creditorKey\":{\"type\":\"EMAIL\",\"value\":\"nobody@example.com\"},\"amount\":\"1.00\"}"
                    .formatted(alice));

    assertThat(none.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat((String) JsonPath.read(none.getBody(), "$.code"))
        .isEqualTo("transfer.creditor.invalid");
    assertThat(unknownKey.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }
}
