package com.instapay.payment.adapters.persistence;

import com.instapay.payment.application.port.out.AccountLocking;
import com.instapay.payment.application.port.out.ConcurrencyConflictException;
import com.instapay.payment.domain.account.Account;
import com.instapay.payment.domain.account.AccountId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Version check at the end of the transaction, the same statement JPA's {@code @Version} issues.
 * Under READ COMMITTED a concurrent UPDATE waits for the first committer and then re-evaluates
 * {@code version = ?}, so exactly one of two racing debits wins.
 */
public class OptimisticAccountLocking implements AccountLocking {

  private final JdbcTemplate jdbc;
  private final Counter conflicts;

  public OptimisticAccountLocking(JdbcTemplate jdbc, MeterRegistry registry) {
    this.jdbc = jdbc;
    this.conflicts =
        Counter.builder("instapay.account.lock.conflicts")
            .description("Optimistic lock conflicts on account debits")
            .register(registry);
  }

  @Override
  public Optional<Account> loadForDebit(AccountId id) {
    return jdbc
        .query(JdbcAccountRepository.SELECT, JdbcAccountRepository.MAPPER, id.value())
        .stream()
        .findFirst();
  }

  @Override
  public void confirmDebit(Account account) {
    int updated =
        jdbc.update(
            "UPDATE account SET version = version + 1 WHERE id = ? AND version = ?",
            account.id().value(),
            account.version());
    if (updated == 0) {
      conflicts.increment();
      throw new ConcurrencyConflictException(
          "Account " + account.id() + " was debited concurrently");
    }
  }
}
