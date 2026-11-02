package com.instapay.payment.adapters.persistence;

import com.instapay.payment.application.port.out.AccountLimitsRepository;
import com.instapay.payment.domain.account.AccountId;
import com.instapay.payment.domain.limits.TransferLimits;
import com.instapay.payment.domain.shared.Money;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAccountLimitsRepository implements AccountLimitsRepository {

  private final JdbcTemplate jdbc;

  public JdbcAccountLimitsRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<TransferLimits> find(AccountId account) {
    return jdbc
        .query(
            "SELECT per_transaction_cents, daily_cents, nightly_cents FROM account_limits WHERE account_id = ?",
            (rs, i) ->
                new TransferLimits(
                    Money.ofCents(rs.getLong("per_transaction_cents")),
                    Money.ofCents(rs.getLong("daily_cents")),
                    Money.ofCents(rs.getLong("nightly_cents"))),
            account.value())
        .stream()
        .findFirst();
  }

  @Override
  public void save(AccountId account, TransferLimits l) {
    jdbc.update(
        """
        INSERT INTO account_limits (account_id, per_transaction_cents, daily_cents, nightly_cents)
        VALUES (?, ?, ?, ?)
        ON CONFLICT (account_id) DO UPDATE SET per_transaction_cents = excluded.per_transaction_cents,
            daily_cents = excluded.daily_cents, nightly_cents = excluded.nightly_cents, updated_at = now()
        """,
        account.value(),
        l.perTransaction().cents(),
        l.daily().cents(),
        l.nightly().cents());
  }
}
