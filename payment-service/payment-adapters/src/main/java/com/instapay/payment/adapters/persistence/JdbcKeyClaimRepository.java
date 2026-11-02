package com.instapay.payment.adapters.persistence;

import com.instapay.payment.application.port.out.KeyClaimRepository;
import com.instapay.payment.domain.account.AccountId;
import com.instapay.payment.domain.pixkey.KeyClaim;
import com.instapay.payment.domain.pixkey.KeyClaimId;
import com.instapay.payment.domain.pixkey.KeyClaimStatus;
import com.instapay.payment.domain.pixkey.KeyClaimType;
import com.instapay.payment.domain.pixkey.PixKey;
import com.instapay.payment.domain.pixkey.PixKeyType;
import com.instapay.payment.domain.shared.DomainException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcKeyClaimRepository implements KeyClaimRepository {

  private static final String SELECT = "SELECT * FROM key_claim WHERE id = ?";

  private static final RowMapper<KeyClaim> MAPPER =
      (rs, i) -> {
        Timestamp resolvedAt = rs.getTimestamp("resolved_at");
        return KeyClaim.restore(
            new KeyClaimId(rs.getObject("id", UUID.class)),
            PixKey.of(PixKeyType.valueOf(rs.getString("key_type")), rs.getString("key_value")),
            KeyClaimType.valueOf(rs.getString("claim_type")),
            new AccountId(rs.getObject("donor_account", UUID.class)),
            new AccountId(rs.getObject("claimer_account", UUID.class)),
            rs.getTimestamp("opened_at").toInstant(),
            rs.getTimestamp("resolution_deadline").toInstant(),
            KeyClaimStatus.valueOf(rs.getString("status")),
            resolvedAt == null ? null : resolvedAt.toInstant());
      };

  private final JdbcTemplate jdbc;

  public JdbcKeyClaimRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(KeyClaim c) {
    try {
      jdbc.update(
          """
          INSERT INTO key_claim (id, key_type, key_value, claim_type, donor_account, claimer_account,
                                 status, opened_at, resolution_deadline, resolved_at)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """,
          c.id().value(),
          c.key().type().name(),
          c.key().value(),
          c.type().name(),
          c.donor().value(),
          c.claimer().value(),
          c.status().name(),
          Timestamp.from(c.openedAt()),
          Timestamp.from(c.resolutionDeadline()),
          c.resolvedAt().map(Timestamp::from).orElse(null));
    } catch (DuplicateKeyException e) {
      throw new DomainException("keyclaim.already_open", "Key already has an open claim");
    }
  }

  @Override
  public Optional<KeyClaim> findById(KeyClaimId id) {
    return jdbc.query(SELECT, MAPPER, id.value()).stream().findFirst();
  }

  @Override
  public Optional<KeyClaim> findByIdForUpdate(KeyClaimId id) {
    return jdbc.query(SELECT + " FOR UPDATE", MAPPER, id.value()).stream().findFirst();
  }

  @Override
  public boolean hasOpenClaim(PixKey key) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT exists(SELECT 1 FROM key_claim WHERE key_type = ? AND key_value = ? AND status = 'OPEN')",
            Boolean.class,
            key.type().name(),
            key.value()));
  }

  @Override
  public void update(KeyClaim c) {
    jdbc.update(
        "UPDATE key_claim SET status = ?, resolved_at = ? WHERE id = ?",
        c.status().name(),
        c.resolvedAt().map(Timestamp::from).orElse(null),
        c.id().value());
  }
}
