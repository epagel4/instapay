package com.instapay.payment.adapters;

import static org.assertj.core.api.Assertions.assertThat;

import com.instapay.payment.domain.shared.Money;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 100 simultaneous HTTP transfers debiting the same account. Run once per locking strategy
 * (subclasses). Invariants checked for both; expected outcome counts differ (ADR-002).
 */
abstract class TransferConcurrencyTest extends IntegrationTest {

  static final int THREADS = 100;
  static final Money AMOUNT = Money.of("10.00");

  @Autowired TestRestTemplate http;
  @Autowired JdbcTemplate jdbc;
  @Autowired MeterRegistry meters;

  record Outcome(
      int succeeded, int insufficientFunds, int conflicts, long elapsedMs, double lockConflicts) {}

  /** 500.00 for 100 x 10.00: only 50 can ever succeed. */
  @Test
  void neverOverdrawsUnderContention() throws Exception {
    Outcome outcome = run(Money.of("500.00"));
    assertScarceFunds(outcome);
  }

  /** 1000.00 for 100 x 10.00: every transfer could succeed. */
  @Test
  void enoughFundsUnderContention() throws Exception {
    Outcome outcome = run(Money.of("1000.00"));
    assertAmpleFunds(outcome);
  }

  abstract void assertScarceFunds(Outcome outcome);

  abstract void assertAmpleFunds(Outcome outcome);

  private Outcome run(Money initial) throws Exception {
    Api api = new Api(http);
    UUID debtor = api.openAccount(), creditor = api.openAccount();
    api.deposit(debtor, initial.toString());
    double conflictsBefore = lockConflicts();

    CountDownLatch start = new CountDownLatch(1);
    long began;
    List<Integer> statuses;
    try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
      List<Future<ResponseEntity<String>>> futures =
          IntStream.range(0, THREADS)
              .mapToObj(
                  i ->
                      pool.submit(
                          () -> {
                            start.await();
                            return api.transfer(Api.key(), debtor, creditor, AMOUNT.toString());
                          }))
              .toList();
      began = System.nanoTime();
      start.countDown();
      statuses =
          futures.stream().map(f -> TransferApiTest.join(f).getStatusCode().value()).toList();
    }
    long elapsedMs = (System.nanoTime() - began) / 1_000_000;

    Map<Integer, Long> byStatus =
        statuses.stream()
            .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    assertThat(byStatus.keySet()).isSubsetOf(201, 409, 422);
    int ok = byStatus.getOrDefault(201, 0L).intValue();
    Outcome outcome =
        new Outcome(
            ok,
            byStatus.getOrDefault(422, 0L).intValue(),
            byStatus.getOrDefault(409, 0L).intValue(),
            elapsedMs,
            lockConflicts() - conflictsBefore);

    // Invariants that must hold for any strategy:
    Money spent = Money.ofCents(AMOUNT.cents() * ok);
    assertThat(Money.of(api.balance(debtor)))
        .isEqualTo(initial.minus(spent))
        .isGreaterThanOrEqualTo(Money.ZERO);
    assertThat(Money.of(api.balance(creditor))).isEqualTo(spent);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM transfer WHERE debtor_account = ?", Long.class, debtor))
        .isEqualTo(ok);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM posting WHERE account_id = ? AND amount_cents < 0",
                Long.class,
                debtor))
        .isEqualTo(ok);
    assertThat(
            jdbc.queryForObject(
                "SELECT coalesce(sum(amount_cents), 0)::bigint FROM posting", Long.class))
        .isZero();

    System.out.printf("[%s] initial=%s -> %s%n", getClass().getSimpleName(), initial, outcome);
    return outcome;
  }

  private double lockConflicts() {
    return Optional.ofNullable(meters.find("instapay.account.lock.conflicts").counter())
        .map(Counter::count)
        .orElse(0.0);
  }
}
