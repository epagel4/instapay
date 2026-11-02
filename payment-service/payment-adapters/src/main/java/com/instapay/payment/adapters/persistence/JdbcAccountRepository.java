package com.instapay.payment.adapters.persistence;

import com.instapay.payment.application.port.out.AccountRepository;
import com.instapay.payment.domain.account.Account;
import com.instapay.payment.domain.account.AccountHolder;
import com.instapay.payment.domain.account.AccountId;
import com.instapay.payment.domain.account.AccountStatus;
import com.instapay.payment.domain.shared.Cpf;
import com.instapay.payment.domain.shared.Ispb;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAccountRepository implements AccountRepository {

  static final String SELECT =
      "SELECT id, institution, holder_name, holder_cpf, status, version FROM account WHERE id = ?";

  static final RowMapper<Account> MAPPER =
      (rs, i) ->
          Account.restore(
              new AccountId(rs.getObject("id", java.util.UUID.class)),
              new Ispb(rs.getString("institution")),
              new AccountHolder(rs.getString("holder_name"), Cpf.of(rs.getString("holder_cpf"))),
              AccountStatus.valueOf(rs.getString("status")),
              rs.getLong("version"));

  private final JdbcTemplate jdbc;

  public JdbcAccountRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(Account account) {
    jdbc.update(
        "INSERT INTO account (id, institution, holder_name, holder_cpf, status, version) VALUES (?, ?, ?, ?, ?, ?)",
        account.id().value(),
        account.institution().value(),
        account.holder().name(),
        account.holder().cpf().value(),
        account.status().name(),
        account.version());
  }

  @Override
  public Optional<Account> findById(AccountId id) {
    return jdbc.query(SELECT, MAPPER, id.value()).stream().findFirst();
  }

  @Override
  public Optional<Account> findByIdForUpdate(AccountId id) {
    return jdbc.query(SELECT + " FOR UPDATE", MAPPER, id.value()).stream().findFirst();
  }
}
