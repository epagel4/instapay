package com.instapay.payment.adapters.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instapay.payment.application.port.out.IdempotencyStore;
import com.instapay.payment.application.port.out.IdempotencyStore.StoredResponse;
import com.instapay.payment.application.port.out.TransactionRunner;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Enforces Idempotency-Key on every write under /api/v1 (ADR-003).
 *
 * <p>The key claim, the business effect and the stored response commit in one transaction: either
 * the operation happened and its response is stored, or neither. Concurrent requests with the same
 * key serialize on the key's primary key and the later ones replay the first response.
 */
@Component
class IdempotencyFilter extends OncePerRequestFilter {

  static final String HEADER = "Idempotency-Key";
  static final String REPLAYED_HEADER = "Idempotent-Replayed";
  private static final int MAX_KEY_LENGTH = 100;
  private static final int MAX_BODY_BYTES = 64 * 1024;
  private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

  private final IdempotencyStore store;
  private final TransactionRunner tx;
  private final ObjectMapper json;

  IdempotencyFilter(IdempotencyStore store, TransactionRunner tx, ObjectMapper json) {
    this.store = store;
    this.tx = tx;
    this.json = json;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !WRITE_METHODS.contains(request.getMethod())
        || !request.getRequestURI().startsWith("/api/v1/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException {
    String key = request.getHeader(HEADER);
    if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
      problem(
          response,
          HttpStatus.BAD_REQUEST,
          "idempotency.key_missing",
          HEADER + " header (1-" + MAX_KEY_LENGTH + " chars) is required for write operations");
      return;
    }
    byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
    if (body.length > MAX_BODY_BYTES) {
      problem(
          response, HttpStatus.PAYLOAD_TOO_LARGE, "request.too_large", "Request body too large");
      return;
    }
    String fingerprint = fingerprint(request, body);

    Optional<StoredResponse> cached = store.findCached(key);
    if (cached.isPresent()) {
      replay(cached.get(), fingerprint, response);
      return;
    }

    ContentCachingResponseWrapper captured = new ContentCachingResponseWrapper(response);
    Outcome outcome;
    try {
      outcome =
          tx.inTransaction(
              () -> {
                Optional<StoredResponse> previous = store.claim(key, fingerprint);
                if (previous.isPresent()) {
                  return new Outcome(previous.get(), true);
                }
                proceed(chain, new CachedBodyRequest(request, body), captured);
                StoredResponse produced = snapshot(fingerprint, captured);
                if (isTransient(produced.status())) {
                  throw new TransientOutcome(); // roll back: the client may retry with this key
                }
                store.complete(key, produced);
                return new Outcome(produced, false);
              });
    } catch (TransientOutcome e) {
      captured.copyBodyToResponse();
      return;
    }

    if (outcome.replayed()) {
      replay(outcome.response(), fingerprint, response);
    } else {
      captured.copyBodyToResponse(); // only after COMMIT: never an uncommitted success
    }
    // The client has its answer; the best-effort cache write stays off the latency path.
    response.flushBuffer();
    store.cache(key, outcome.response()); // also re-warms the cache after a Postgres replay
  }

  private record Outcome(StoredResponse response, boolean replayed) {}

  private void replay(StoredResponse stored, String fingerprint, HttpServletResponse response)
      throws IOException {
    if (!stored.fingerprint().equals(fingerprint)) {
      problem(
          response,
          HttpStatus.UNPROCESSABLE_ENTITY,
          "idempotency.key_reused",
          HEADER + " was already used with a different request");
      return;
    }
    response.setStatus(stored.status());
    if (stored.contentType() != null && !stored.contentType().isEmpty()) {
      response.setContentType(stored.contentType());
    }
    response.setHeader(REPLAYED_HEADER, "true");
    response.getOutputStream().write(stored.body().getBytes(StandardCharsets.UTF_8));
  }

  private static void proceed(
      FilterChain chain, HttpServletRequest request, HttpServletResponse response) {
    try {
      chain.doFilter(request, response);
    } catch (IOException | ServletException e) {
      throw new IllegalStateException(e);
    }
  }

  private static StoredResponse snapshot(String fingerprint, ContentCachingResponseWrapper r) {
    return new StoredResponse(
        fingerprint,
        r.getStatus(),
        r.getContentType(),
        new String(r.getContentAsByteArray(), StandardCharsets.UTF_8));
  }

  /** Not stored, so a retry with the same key re-executes: conflicts, throttling, server errors. */
  static boolean isTransient(int status) {
    return status == 409 || status == 429 || status >= 500;
  }

  private void problem(HttpServletResponse response, HttpStatus status, String code, String detail)
      throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    json.writeValue(response.getOutputStream(), Problems.of(status, code, detail));
  }

  private static String fingerprint(HttpServletRequest request, byte[] body) {
    try {
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      sha.update(
          (request.getMethod() + " " + request.getRequestURI() + "\n")
              .getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(sha.digest(body));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static final class TransientOutcome extends RuntimeException {
    TransientOutcome() {
      super(null, null, false, false);
    }
  }
}
