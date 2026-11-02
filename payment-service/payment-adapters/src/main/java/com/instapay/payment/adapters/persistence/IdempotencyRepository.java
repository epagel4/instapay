package com.instapay.payment.adapters.persistence;

import com.instapay.payment.application.port.out.IdempotencyStore;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Postgres is the source of truth; Redis is a read-through cache that may be unavailable. */
@Repository
public class IdempotencyRepository implements IdempotencyStore {

  private static final Logger log = LoggerFactory.getLogger(IdempotencyRepository.class);
  private static final Duration CACHE_TTL = Duration.ofHours(24);

  private final JdbcTemplate jdbc;
  private final StringRedisTemplate redis;

  public IdempotencyRepository(JdbcTemplate jdbc, StringRedisTemplate redis) {
    this.jdbc = jdbc;
    this.redis = redis;
  }

  @Override
  public Optional<StoredResponse> findCached(String key) {
    try {
      Map<Object, Object> hash = redis.opsForHash().entries(redisKey(key));
      if (hash.isEmpty()) {
        return Optional.empty();
      }
      return Optional.of(
          new StoredResponse(
              (String) hash.get("fingerprint"),
              Integer.parseInt((String) hash.get("status")),
              (String) hash.get("contentType"),
              (String) hash.get("body")));
    } catch (RuntimeException e) {
      log.warn("Idempotency cache unavailable, falling back to Postgres: {}", e.toString());
      return Optional.empty();
    }
  }

  @Override
  public Optional<StoredResponse> claim(String key, String fingerprint) {
    // Blocks on the primary key while another transaction holds the same key uncommitted.
    int inserted =
        jdbc.update(
            "INSERT INTO idempotency_record (key, fingerprint) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
            key,
            fingerprint);
    if (inserted == 1) {
      return Optional.empty();
    }
    return jdbc
        .query(
            "SELECT fingerprint, status_code, content_type, body FROM idempotency_record WHERE key = ?",
            (rs, i) ->
                new StoredResponse(
                    rs.getString("fingerprint"),
                    rs.getInt("status_code"),
                    rs.getString("content_type"),
                    rs.getString("body")),
            key)
        .stream()
        .findFirst();
  }

  @Override
  public void complete(String key, StoredResponse response) {
    jdbc.update(
        "UPDATE idempotency_record SET status_code = ?, content_type = ?, body = ? WHERE key = ?",
        response.status(),
        response.contentType(),
        response.body(),
        key);
  }

  @Override
  public void cache(String key, StoredResponse response) {
    try {
      String redisKey = redisKey(key);
      redis
          .opsForHash()
          .putAll(
              redisKey,
              Map.of(
                  "fingerprint", response.fingerprint(),
                  "status", String.valueOf(response.status()),
                  "contentType", response.contentType() == null ? "" : response.contentType(),
                  "body", response.body()));
      redis.expire(redisKey, CACHE_TTL);
    } catch (RuntimeException e) {
      log.warn("Could not cache idempotent response: {}", e.toString());
    }
  }

  private static String redisKey(String key) {
    return "idempotency:" + key;
  }
}
