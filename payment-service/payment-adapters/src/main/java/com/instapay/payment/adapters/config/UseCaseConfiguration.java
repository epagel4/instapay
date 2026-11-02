package com.instapay.payment.adapters.config;

import com.instapay.payment.adapters.persistence.JdbcAccountRepository;
import com.instapay.payment.adapters.persistence.OptimisticAccountLocking;
import com.instapay.payment.adapters.persistence.PessimisticAccountLocking;
import com.instapay.payment.application.account.AccountService;
import com.instapay.payment.application.limits.LimitService;
import com.instapay.payment.application.pixkey.PixKeyService;
import com.instapay.payment.application.port.out.AccountLimitsRepository;
import com.instapay.payment.application.port.out.AccountLocking;
import com.instapay.payment.application.port.out.AccountRepository;
import com.instapay.payment.application.port.out.AuditLog;
import com.instapay.payment.application.port.out.KeyClaimRepository;
import com.instapay.payment.application.port.out.LedgerRepository;
import com.instapay.payment.application.port.out.PixKeyRepository;
import com.instapay.payment.application.port.out.RefundRepository;
import com.instapay.payment.application.port.out.SettlementGateway;
import com.instapay.payment.application.port.out.SettlementSagaRepository;
import com.instapay.payment.application.port.out.TransactionRunner;
import com.instapay.payment.application.port.out.TransferRepository;
import com.instapay.payment.application.transfer.RefundService;
import com.instapay.payment.application.transfer.SettlementSagaService;
import com.instapay.payment.application.transfer.TransferService;
import com.instapay.payment.domain.limits.TransferLimits;
import com.instapay.payment.domain.shared.Ispb;
import com.instapay.payment.domain.shared.Money;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the framework-free use cases (ADR-001). */
@Configuration(proxyBeanMethods = false)
class UseCaseConfiguration {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  /** ADR-002: pessimistic by default; optimistic kept for comparison. */
  @Bean
  AccountLocking accountLocking(
      @Value("${instapay.locking.strategy}") String strategy,
      JdbcAccountRepository accounts,
      JdbcTemplate jdbc,
      MeterRegistry registry) {
    return switch (strategy) {
      case "pessimistic" -> new PessimisticAccountLocking(accounts);
      case "optimistic" -> new OptimisticAccountLocking(jdbc, registry);
      default ->
          throw new IllegalArgumentException("Unknown instapay.locking.strategy: " + strategy);
    };
  }

  @Bean
  AccountService accountService(
      AccountRepository accounts,
      LedgerRepository ledger,
      TransactionRunner tx,
      Clock clock,
      @Value("${instapay.institution-ispb}") String ispb) {
    return new AccountService(accounts, ledger, tx, clock, new Ispb(ispb));
  }

  @Bean
  TransferService transferService(
      AccountRepository accounts,
      AccountLocking locking,
      LedgerRepository ledger,
      TransferRepository transfers,
      SettlementSagaRepository sagas,
      SettlementGateway settlement,
      AuditLog audit,
      LimitService limits,
      TransactionRunner tx,
      Clock clock,
      @Value("${instapay.institution-ispb}") String ispb,
      @Value("${instapay.locking.max-attempts}") int maxAttempts,
      @Value("${instapay.settlement.timeout}") Duration settlementTimeout,
      @Value("${instapay.settlement.compensation-grace}") Duration grace) {
    return new TransferService(
        accounts,
        locking,
        ledger,
        transfers,
        sagas,
        settlement,
        audit,
        limits,
        tx,
        clock,
        new TransferService.Settings(new Ispb(ispb), maxAttempts, settlementTimeout, grace));
  }

  @Bean
  SettlementSagaService settlementSagaService(
      SettlementSagaRepository sagas,
      TransferRepository transfers,
      LedgerRepository ledger,
      AuditLog audit,
      TransactionRunner tx,
      Clock clock) {
    return new SettlementSagaService(sagas, transfers, ledger, audit, tx, clock);
  }

  @Bean
  PixKeyService pixKeyService(
      AccountRepository accounts,
      PixKeyRepository keys,
      KeyClaimRepository claims,
      TransactionRunner tx,
      Clock clock) {
    return new PixKeyService(accounts, keys, claims, tx, clock);
  }

  @Bean
  LimitService limitService(
      AccountLimitsRepository limits,
      AccountRepository accounts,
      TransferRepository transfers,
      TransactionRunner tx,
      Clock clock,
      @Value("${instapay.limits.default.per-transaction}") String perTransaction,
      @Value("${instapay.limits.default.daily}") String daily,
      @Value("${instapay.limits.default.nightly}") String nightly,
      @Value("${instapay.limits.zone}") String zone) {
    TransferLimits defaults =
        new TransferLimits(Money.of(perTransaction), Money.of(daily), Money.of(nightly));
    return new LimitService(limits, accounts, transfers, tx, clock, defaults, ZoneId.of(zone));
  }

  @Bean
  RefundService refundService(
      TransferRepository transfers,
      RefundRepository refunds,
      AccountRepository accounts,
      AccountLocking locking,
      LedgerRepository ledger,
      AuditLog audit,
      TransactionRunner tx,
      Clock clock,
      @Value("${instapay.locking.max-attempts}") int maxAttempts) {
    return new RefundService(
        transfers, refunds, accounts, locking, ledger, audit, tx, clock, maxAttempts);
  }
}
