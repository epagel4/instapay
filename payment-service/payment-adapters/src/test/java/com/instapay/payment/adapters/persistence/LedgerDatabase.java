package com.instapay.payment.adapters.persistence;

import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Real Postgres with the Flyway schema, without a Spring context (jqwik properties run outside the
 * Spring test extension). One container shared by all ledger tests; data is isolated by using fresh
 * account ids, since the ledger tables forbid DELETE/TRUNCATE.
 */
final class LedgerDatabase {

  static final JdbcTemplate JDBC;
  static final TransactionTemplate TX;
  static final JdbcLedgerRepository REPOSITORY;

  static {
    PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    postgres.start();
    DataSource dataSource =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    Flyway.configure().dataSource(dataSource).load().migrate();
    JDBC = new JdbcTemplate(dataSource);
    TX = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    REPOSITORY = new JdbcLedgerRepository(JDBC);
  }

  private LedgerDatabase() {}

  /** Inserts raw rows, bypassing the domain model, in one transaction. */
  static void insertRawEntry(UUID entryId, long... postingCents) {
    TX.executeWithoutResult(
        status -> {
          JDBC.update(
              "INSERT INTO journal_entry (id, type, reference, occurred_at) VALUES (?, 'TRANSFER', ?, now())",
              entryId,
              entryId);
          for (long cents : postingCents) {
            JDBC.update(
                "INSERT INTO posting (journal_entry_id, account_id, amount_cents) VALUES (?, ?, ?)",
                entryId,
                UUID.randomUUID(),
                cents);
          }
        });
  }

  static long sumOfAllPostings() {
    return JDBC.queryForObject(
        "SELECT coalesce(sum(amount_cents), 0)::bigint FROM posting", Long.class);
  }

  static long unbalancedEntries() {
    return JDBC.queryForObject(
        """
        SELECT count(*) FROM journal_entry j
        WHERE (SELECT coalesce(sum(amount_cents), 0) FROM posting p WHERE p.journal_entry_id = j.id) <> 0
           OR (SELECT count(*) FROM posting p WHERE p.journal_entry_id = j.id) < 2
        """,
        Long.class);
  }

  static boolean entryExists(UUID entryId) {
    return JDBC.queryForObject(
        "SELECT exists(SELECT 1 FROM journal_entry WHERE id = ?)", Boolean.class, entryId);
  }
}
