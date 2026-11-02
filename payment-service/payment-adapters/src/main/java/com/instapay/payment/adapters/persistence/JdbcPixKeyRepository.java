package com.instapay.payment.adapters.persistence;

import com.instapay.payment.application.port.out.PixKeyRepository;
import com.instapay.payment.domain.account.AccountId;
import com.instapay.payment.domain.pixkey.PixKey;
import com.instapay.payment.domain.pixkey.PixKeyRegistration;
import com.instapay.payment.domain.pixkey.PixKeyType;
import com.instapay.payment.domain.shared.DomainException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPixKeyRepository implements PixKeyRepository {

  private static final String SELECT =
      "SELECT type, value, account_id, created_at FROM pix_key WHERE type = ? AND value = ?";

  private static final RowMapper<PixKeyRegistration> MAPPER =
      (rs, i) ->
          new PixKeyRegistration(
              PixKey.of(PixKeyType.valueOf(rs.getString("type")), rs.getString("value")),
              new AccountId(rs.getObject("account_id", UUID.class)),
              rs.getTimestamp("created_at").toInstant());

  private final JdbcTemplate jdbc;

  public JdbcPixKeyRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(PixKeyRegistration r) {
    try {
      jdbc.update(
          "INSERT INTO pix_key (type, value, account_id, created_at) VALUES (?, ?, ?, ?)",
          r.key().type().name(),
          r.key().value(),
          r.account().value(),
          Timestamp.from(r.createdAt()));
    } catch (DuplicateKeyException e) {
      // The primary key is the source of truth for uniqueness, also under concurrent inserts.
      throw new DomainException("pixkey.already_registered", "Key is already registered");
    }
  }

  @Override
  public Optional<PixKeyRegistration> find(PixKey key) {
    return jdbc.query(SELECT, MAPPER, key.type().name(), key.value()).stream().findFirst();
  }

  @Override
  public Optional<PixKeyRegistration> findForUpdate(PixKey key) {
    return jdbc.query(SELECT + " FOR UPDATE", MAPPER, key.type().name(), key.value()).stream()
        .findFirst();
  }

  @Override
  public List<PixKeyRegistration> listByAccount(AccountId account) {
    return jdbc.query(
        "SELECT type, value, account_id, created_at FROM pix_key WHERE account_id = ? ORDER BY created_at",
        MAPPER,
        account.value());
  }

  @Override
  public int countByAccount(AccountId account) {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM pix_key WHERE account_id = ?", Integer.class, account.value());
    return count == null ? 0 : count;
  }

  @Override
  public void update(PixKeyRegistration r) {
    jdbc.update(
        "UPDATE pix_key SET account_id = ?, created_at = ? WHERE type = ? AND value = ?",
        r.account().value(),
        Timestamp.from(r.createdAt()),
        r.key().type().name(),
        r.key().value());
  }

  @Override
  public void delete(PixKey key) {
    jdbc.update("DELETE FROM pix_key WHERE type = ? AND value = ?", key.type().name(), key.value());
  }
}
