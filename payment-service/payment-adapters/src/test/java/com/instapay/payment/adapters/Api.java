package com.instapay.payment.adapters;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Thin HTTP client for tests. Bodies are raw JSON strings so replays can be compared byte-wise. */
record Api(TestRestTemplate http) {

  ResponseEntity<String> post(String path, String idempotencyKey, String json) {
    return send(HttpMethod.POST, path, idempotencyKey, json);
  }

  ResponseEntity<String> send(HttpMethod method, String path, String idempotencyKey, String json) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (idempotencyKey != null) {
      headers.set("Idempotency-Key", idempotencyKey);
    }
    return http.exchange(path, method, new HttpEntity<>(json, headers), String.class);
  }

  UUID openAccount() {
    return openAccount("529.982.247-25");
  }

  UUID openAccount(String cpf) {
    var response =
        post(
            "/api/v1/accounts",
            key(),
            "{\"holderName\":\"Maria Silva\",\"holderCpf\":\"" + cpf + "\"}");
    return UUID.fromString(JsonPath.read(response.getBody(), "$.id"));
  }

  ResponseEntity<String> deposit(UUID account, String amount) {
    return post(
        "/api/v1/accounts/" + account + "/deposits", key(), "{\"amount\":\"" + amount + "\"}");
  }

  ResponseEntity<String> transfer(String key, UUID from, UUID to, String amount) {
    return post(
        "/api/v1/transfers",
        key,
        "{\"debtorAccountId\":\"%s\",\"creditorAccountId\":\"%s\",\"amount\":\"%s\"}"
            .formatted(from, to, amount));
  }

  String balance(UUID account) {
    return JsonPath.read(
        http.getForObject("/api/v1/accounts/" + account, String.class), "$.balance");
  }

  static String key() {
    return UUID.randomUUID().toString();
  }
}
