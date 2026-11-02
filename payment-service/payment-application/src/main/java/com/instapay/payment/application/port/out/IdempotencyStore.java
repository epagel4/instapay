package com.instapay.payment.application.port.out;

import java.util.Optional;

/** Responses stored per Idempotency-Key (ADR-003). */
public interface IdempotencyStore {

  record StoredResponse(String fingerprint, int status, String contentType, String body) {}

  /** Fast path (cache). Best effort: empty if the cache is unavailable. */
  Optional<StoredResponse> findCached(String key);

  /**
   * Must run inside a transaction. Claims the key for this transaction (empty result), or returns
   * the response committed by an earlier request with the same key. A concurrent request holding
   * the key makes this call wait until it commits or rolls back.
   */
  Optional<StoredResponse> claim(String key, String fingerprint);

  /** Stores the response in the claiming transaction. */
  void complete(String key, StoredResponse response);

  /** Populates the cache after commit. Best effort. */
  void cache(String key, StoredResponse response);
}
