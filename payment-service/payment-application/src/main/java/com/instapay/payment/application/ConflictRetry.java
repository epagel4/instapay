package com.instapay.payment.application;

import com.instapay.payment.application.port.out.ConcurrencyConflictException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/** Re-runs a whole transactional attempt on optimistic-lock conflicts (ADR-002). */
public final class ConflictRetry {

  private ConflictRetry() {}

  public static <T> T run(int maxAttempts, Supplier<T> attempt) {
    for (int n = 1; ; n++) {
      try {
        return attempt.get();
      } catch (ConcurrencyConflictException e) {
        if (n >= maxAttempts) {
          throw e;
        }
        backoff(n);
      }
    }
  }

  /** Jittered linear backoff, so retrying writers spread out instead of colliding again. */
  private static void backoff(int attempt) {
    try {
      Thread.sleep(ThreadLocalRandom.current().nextLong(1, 5L * attempt + 1));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ConcurrencyConflictException("Interrupted while retrying");
    }
  }
}
