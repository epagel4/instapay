package com.instapay.perf;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Internal transfers between random funded accounts (open model: arrivals per second, as real
 * traffic). Target (spec): p99 < 200 ms at 500 req/s.
 *
 * <p>System properties: perf.baseUrl, perf.profile (load | spike), perf.rps, perf.duration
 * (seconds at target), perf.accounts.
 */
public class InternalTransferSimulation extends Simulation {

  static final String BASE = System.getProperty("perf.baseUrl", "http://localhost:8080");
  static final String PROFILE = System.getProperty("perf.profile", "load");
  static final int RPS = Integer.getInteger("perf.rps", 500);
  static final int DURATION = Integer.getInteger("perf.duration", 120);
  static final int ACCOUNTS = Integer.getInteger("perf.accounts", 2000);
  static final Pattern ID = Pattern.compile("\"id\":\"([^\"]+)\"");

  final List<String> accounts = new CopyOnWriteArrayList<>();

  /** Creates and funds the accounts outside the measurement. */
  @Override
  public void before() {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    try (ExecutorService pool = Executors.newFixedThreadPool(32)) {
      List<Future<?>> jobs = new ArrayList<>();
      for (int i = 0; i < ACCOUNTS; i++) {
        jobs.add(
            pool.submit(
                () -> {
                  String body =
                      post(
                          client,
                          "/api/v1/accounts",
                          "{\"holderName\":\"Perf\",\"holderCpf\":\"529.982.247-25\"}");
                  Matcher m = ID.matcher(body);
                  if (!m.find()) {
                    throw new IllegalStateException("Account not created: " + body);
                  }
                  String id = m.group(1);
                  post(client, "/api/v1/accounts/" + id + "/deposits", "{\"amount\":\"100000.00\"}");
                  accounts.add(id);
                  return null;
                }));
      }
      for (Future<?> job : jobs) {
        job.get();
      }
    } catch (Exception e) {
      throw new IllegalStateException("Setup failed", e);
    }
    System.out.printf("Setup: %d funded accounts%n", accounts.size());
  }

  static String post(HttpClient client, String path, String json) {
    try {
      HttpResponse<String> r =
          client.send(
              HttpRequest.newBuilder(URI.create(BASE + path))
                  .header("Content-Type", "application/json")
                  .header("Idempotency-Key", UUID.randomUUID().toString())
                  .POST(HttpRequest.BodyPublishers.ofString(json))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (r.statusCode() >= 300) {
        throw new IllegalStateException(path + " -> " + r.statusCode() + " " + r.body());
      }
      return r.body();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** Random distinct pair; amounts in cents keep every account far from its limits. */
  Iterator<Map<String, Object>> pairs() {
    Supplier<Map<String, Object>> next =
        () -> {
          ThreadLocalRandom rnd = ThreadLocalRandom.current();
          int a = rnd.nextInt(accounts.size());
          int b = (a + 1 + rnd.nextInt(accounts.size() - 1)) % accounts.size();
          return Map.of(
              "debtor", accounts.get(a),
              "creditor", accounts.get(b),
              "amount", "0.%02d".formatted(1 + rnd.nextInt(99)),
              "key", UUID.randomUUID().toString());
        };
    return java.util.stream.Stream.generate(next).iterator();
  }

  HttpProtocolBuilder protocol =
      http.baseUrl(BASE)
          .contentTypeHeader("application/json")
          .acceptHeader("application/json")
          .shareConnections(); // connection pool like a gateway in front of the service

  ScenarioBuilder transfer =
      scenario("Transferência interna")
          .feed(pairs())
          .exec(
              http("POST /transfers")
                  .post("/api/v1/transfers")
                  .header("Idempotency-Key", "#{key}")
                  .body(
                      StringBody(
                          "{\"debtorAccountId\":\"#{debtor}\",\"creditorAccountId\":\"#{creditor}\",\"amount\":\"#{amount}\"}"))
                  .check(status().is(201)));

  {
    PopulationBuilder population =
        switch (PROFILE) {
          case "load" ->
              transfer.injectOpen(
                  rampUsersPerSec(10).to(RPS).during(30), constantUsersPerSec(RPS).during(DURATION));
          // Steady base, sudden 2x the target, back to base: does latency recover?
          case "spike" ->
              transfer.injectOpen(
                  constantUsersPerSec(RPS / 5.0).during(30),
                  constantUsersPerSec(RPS * 2.0).during(30),
                  constantUsersPerSec(RPS / 5.0).during(60));
          default -> throw new IllegalArgumentException("perf.profile: " + PROFILE);
        };
    SetUp setUp = setUp(population).protocols(protocol);
    if (PROFILE.equals("load")) {
      setUp.assertions(
          global().responseTime().percentile(99.0).lt(200),
          global().failedRequests().percent().lt(1.0));
    } else {
      setUp.assertions(global().failedRequests().percent().lt(1.0));
    }
  }
}
