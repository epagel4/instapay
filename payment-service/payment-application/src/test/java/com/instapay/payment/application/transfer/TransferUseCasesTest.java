package com.instapay.payment.application.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.instapay.payment.application.ResourceNotFoundException;
import com.instapay.payment.application.limits.LimitService;
import com.instapay.payment.application.port.out.AccountLimitsRepository;
import com.instapay.payment.application.port.out.AccountLocking;
import com.instapay.payment.application.port.out.AccountRepository;
import com.instapay.payment.application.port.out.AuditLog;
import com.instapay.payment.application.port.out.ConcurrencyConflictException;
import com.instapay.payment.application.port.out.LedgerRepository;
import com.instapay.payment.application.port.out.SettlementGateway;
import com.instapay.payment.application.port.out.SettlementSagaRepository;
import com.instapay.payment.application.port.out.TransactionRunner;
import com.instapay.payment.application.port.out.TransferRepository;
import com.instapay.payment.application.transfer.SettlementSagaService.Outcome;
import com.instapay.payment.application.transfer.TransferService.TransferCommand;
import com.instapay.payment.domain.account.Account;
import com.instapay.payment.domain.account.AccountHolder;
import com.instapay.payment.domain.account.AccountId;
import com.instapay.payment.domain.account.AccountRef;
import com.instapay.payment.domain.ledger.JournalEntry;
import com.instapay.payment.domain.ledger.JournalEntryId;
import com.instapay.payment.domain.ledger.JournalEntryType;
import com.instapay.payment.domain.ledger.SystemAccounts;
import com.instapay.payment.domain.limits.TransferLimits;
import com.instapay.payment.domain.shared.Cpf;
import com.instapay.payment.domain.shared.DomainEvent;
import com.instapay.payment.domain.shared.DomainException;
import com.instapay.payment.domain.shared.Ispb;
import com.instapay.payment.domain.shared.Money;
import com.instapay.payment.domain.transfer.SagaState;
import com.instapay.payment.domain.transfer.SettlementSaga;
import com.instapay.payment.domain.transfer.Transfer;
import com.instapay.payment.domain.transfer.TransferId;
import com.instapay.payment.domain.transfer.TransferStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TransferUseCasesTest {

  static final Instant NOW = Instant.parse("2026-11-02T15:00:00Z");
  static final Ispb BANK = new Ispb("00000001");
  static final Ispb OTHER_BANK = new Ispb("00000002");
  static final AccountHolder HOLDER = new AccountHolder("Maria", Cpf.of("52998224725"));

  final Map<AccountId, Account> accounts = new HashMap<>();
  final List<JournalEntry> journal = new ArrayList<>();
  final List<Transfer> saved = new ArrayList<>();
  final Account alice = Account.open(AccountId.newId(), BANK, HOLDER);
  final Account bob = Account.open(AccountId.newId(), BANK, HOLDER);

  final Map<TransferId, SettlementSaga> sagaStore = new HashMap<>();
  final List<Transfer> settlementRequests = new ArrayList<>();
  final List<DomainEvent> events = new ArrayList<>();
  final MutableClock clock = new MutableClock();
  int conflictsToThrow;
  int attempts;

  /** Fixed clock that tests can move forward. */
  static final class MutableClock extends Clock {
    Instant now = NOW;

    public Instant instant() {
      return now;
    }

    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }
  }

  final SettlementSagaRepository sagas =
      new SettlementSagaRepository() {
        public void insert(SettlementSaga saga) {
          sagaStore.put(saga.transferId(), saga);
        }

        public Optional<SettlementSaga> findByIdForUpdate(TransferId id) {
          return Optional.ofNullable(sagaStore.get(id));
        }

        public List<SettlementSaga> lockOverdue(Instant now, int limit) {
          return sagaStore.values().stream()
              .filter(s -> s.isAwaiting() && !now.isBefore(s.compensateAfter()))
              .limit(limit)
              .toList();
        }

        public void update(SettlementSaga saga) {}
      };

  final SettlementGateway gateway = (transfer, expiresAt) -> settlementRequests.add(transfer);
  final AuditLog audit = events::add;

  final AccountRepository accountRepo =
      new AccountRepository() {
        public void insert(Account a) {
          accounts.put(a.id(), a);
        }

        public Optional<Account> findById(AccountId id) {
          return Optional.ofNullable(accounts.get(id));
        }

        public Optional<Account> findByIdForUpdate(AccountId id) {
          return findById(id);
        }
      };

  final AccountLocking locking =
      new AccountLocking() {
        public Optional<Account> loadForDebit(AccountId id) {
          attempts++;
          return accountRepo.findById(id);
        }

        public void confirmDebit(Account account) {
          if (conflictsToThrow-- > 0) {
            throw new ConcurrencyConflictException("simulated");
          }
        }
      };

  final LedgerRepository ledger =
      new LedgerRepository() {
        public void append(JournalEntry entry) {
          journal.add(entry);
        }

        public Money balanceOf(AccountId account) {
          return journal.stream().map(e -> e.netAmountFor(account)).reduce(Money.ZERO, Money::plus);
        }
      };

  final TransferRepository transferRepo =
      new TransferRepository() {
        public void insert(Transfer t) {
          saved.add(t);
        }

        public Optional<Transfer> findById(TransferId id) {
          return saved.stream().filter(t -> t.id().equals(id)).findFirst();
        }

        public void update(Transfer t) {}

        public Optional<Transfer> findByIdForUpdate(TransferId id) {
          return findById(id);
        }

        public Money sumOutgoing(AccountId debtor, Instant from, Instant to) {
          return saved.stream()
              .filter(
                  t -> t.debtor().account().equals(debtor) && t.status() != TransferStatus.FAILED)
              .filter(t -> !t.createdAt().isBefore(from) && t.createdAt().isBefore(to))
              .map(Transfer::amount)
              .reduce(Money.ZERO, Money::plus);
        }
      };

  final Map<AccountId, TransferLimits> customLimits = new HashMap<>();
  final AccountLimitsRepository limitsRepo =
      new AccountLimitsRepository() {
        public Optional<TransferLimits> find(AccountId account) {
          return Optional.ofNullable(customLimits.get(account));
        }

        public void save(AccountId account, TransferLimits limits) {
          customLimits.put(account, limits);
        }
      };

  /** Defaults large enough not to interfere with the non-limit tests. */
  static final TransferLimits DEFAULT_LIMITS =
      new TransferLimits(Money.of("1000"), Money.of("5000"), Money.of("1000"));

  /** Rolls back the in-memory writes on failure, like a real transaction. */
  final TransactionRunner tx =
      new TransactionRunner() {
        public <T> T inTransaction(Supplier<T> work) {
          int journalSize = journal.size(), savedSize = saved.size();
          try {
            return work.get();
          } catch (RuntimeException e) {
            journal.subList(journalSize, journal.size()).clear();
            saved.subList(savedSize, saved.size()).clear();
            throw e;
          }
        }
      };

  TransferService service(int maxAttempts) {
    return new TransferService(
        accountRepo,
        locking,
        ledger,
        transferRepo,
        sagas,
        gateway,
        audit,
        new LimitService(
            limitsRepo,
            accountRepo,
            transferRepo,
            tx,
            clock,
            DEFAULT_LIMITS,
            ZoneId.of("America/Sao_Paulo")),
        tx,
        clock,
        new TransferService.Settings(
            BANK, maxAttempts, Duration.ofSeconds(10), Duration.ofSeconds(5)));
  }

  SettlementSagaService sagaService() {
    return new SettlementSagaService(sagas, transferRepo, ledger, audit, tx, clock);
  }

  TransferCommand internal(Account to, String amount) {
    return new TransferCommand(alice.id(), to.ref(), Money.of(amount));
  }

  TransferCommand external(String amount) {
    return new TransferCommand(
        alice.id(), new AccountRef(OTHER_BANK, AccountId.newId()), Money.of(amount));
  }

  @BeforeEach
  void fundAlice() {
    accountRepo.insert(alice);
    accountRepo.insert(bob);
    journal.add(JournalEntry.deposit(JournalEntryId.newId(), alice.id(), Money.of("100"), NOW));
  }

  @Test
  void completesTransferAndPostsToLedger() {
    Transfer t = service(1).transfer(new TransferCommand(alice.id(), bob.ref(), Money.of("40")));

    assertThat(t.status()).isEqualTo(TransferStatus.COMPLETED);
    assertThat(t.finishedAt()).contains(NOW);
    assertThat(ledger.balanceOf(alice.id())).isEqualTo(Money.of("60"));
    assertThat(ledger.balanceOf(bob.id())).isEqualTo(Money.of("40"));
    assertThat(journal.getLast().reference()).isEqualTo(t.id().value());
    assertThat(service(1).get(t.id())).isEqualTo(t);
  }

  @Test
  void rejectsInsufficientFundsWithoutRetryOrWrites() {
    assertThatThrownBy(
            () ->
                service(5).transfer(new TransferCommand(alice.id(), bob.ref(), Money.of("100.01"))))
        .extracting("code")
        .isEqualTo("transfer.insufficient_funds");
    assertThat(attempts).isEqualTo(1);
    assertThat(saved).isEmpty();
    assertThat(journal).hasSize(1);
  }

  @Test
  void spendingTheExactBalanceIsAllowed() {
    service(1).transfer(new TransferCommand(alice.id(), bob.ref(), Money.of("100")));

    assertThat(ledger.balanceOf(alice.id())).isEqualTo(Money.ZERO);
  }

  @Test
  void retriesConcurrencyConflictsUntilSuccess() {
    conflictsToThrow = 3;

    Transfer t = service(5).transfer(new TransferCommand(alice.id(), bob.ref(), Money.of("10")));

    assertThat(attempts).isEqualTo(4);
    assertThat(saved).containsExactly(t);
    assertThat(ledger.balanceOf(alice.id())).isEqualTo(Money.of("90"));
  }

  @Test
  void givesUpAfterMaxAttemptsWithNothingWritten() {
    conflictsToThrow = Integer.MAX_VALUE;

    assertThatThrownBy(
            () -> service(5).transfer(new TransferCommand(alice.id(), bob.ref(), Money.of("10"))))
        .isInstanceOf(ConcurrencyConflictException.class);
    assertThat(attempts).isEqualTo(5);
    assertThat(saved).isEmpty();
    assertThat(ledger.balanceOf(alice.id())).isEqualTo(Money.of("100"));
  }

  @Test
  void blockedDebtorCannotSend() {
    alice.block();

    assertThatThrownBy(
            () -> service(1).transfer(new TransferCommand(alice.id(), bob.ref(), Money.of("1"))))
        .isInstanceOf(DomainException.class)
        .extracting("code")
        .isEqualTo("account.debit.not_allowed");
  }

  @Test
  void unknownAccountsAndTransfersAreNotFound() {
    AccountId ghost = AccountId.newId();

    assertThatThrownBy(
            () -> service(1).transfer(new TransferCommand(ghost, bob.ref(), Money.of("1"))))
        .isInstanceOf(ResourceNotFoundException.class);
    assertThatThrownBy(
            () ->
                service(1)
                    .transfer(
                        new TransferCommand(
                            alice.id(), new AccountRef(BANK, ghost), Money.of("1"))))
        .isInstanceOf(ResourceNotFoundException.class)
        .extracting("code")
        .isEqualTo("account.not_found");
    assertThatThrownBy(() -> service(1).get(TransferId.newId()))
        .extracting("code")
        .isEqualTo("transfer.not_found");
  }

  // ---------- external transfers & saga ----------

  @Test
  void externalTransferHoldsFundsAndRequestsSettlement() {
    Transfer t = service(1).transfer(external("30"));

    assertThat(t.status()).isEqualTo(TransferStatus.PENDING);
    assertThat(ledger.balanceOf(alice.id())).isEqualTo(Money.of("70"));
    assertThat(ledger.balanceOf(SystemAccounts.SETTLEMENT_IN_TRANSIT)).isEqualTo(Money.of("30"));
    assertThat(journal.getLast().type()).isEqualTo(JournalEntryType.EXTERNAL_TRANSFER);
    assertThat(sagaStore.get(t.id()).state()).isEqualTo(SagaState.AWAITING_SETTLEMENT);
    assertThat(settlementRequests).containsExactly(t);
    assertThat(events)
        .extracting(DomainEvent::eventType)
        .containsExactly("TransferSettlementRequested");
  }

  @Test
  void internalTransferIsAudited() {
    service(1).transfer(internal(bob, "1"));

    assertThat(events).extracting(DomainEvent::eventType).containsExactly("TransferCompleted");
    assertThat(settlementRequests).isEmpty();
  }

  @Test
  void settledSagaCompletesTransferAndMovesFundsOutOfTransit() {
    Transfer t = service(1).transfer(external("30"));

    assertThat(sagaService().onSettlementResult(t.id(), true, null)).isEqualTo(Outcome.COMPLETED);

    assertThat(t.status()).isEqualTo(TransferStatus.COMPLETED);
    assertThat(ledger.balanceOf(SystemAccounts.SETTLEMENT_IN_TRANSIT)).isEqualTo(Money.ZERO);
    assertThat(ledger.balanceOf(SystemAccounts.SETTLEMENT)).isEqualTo(Money.of("30"));
    assertThat(ledger.balanceOf(alice.id())).isEqualTo(Money.of("70"));
    assertThat(sagaService().onSettlementResult(t.id(), true, null)).isEqualTo(Outcome.DUPLICATE);
    assertThat(journal).filteredOn(e -> e.type() == JournalEntryType.SETTLEMENT).hasSize(1);
  }

  @Test
  void rejectedSagaCompensatesDebtor() {
    Transfer t = service(1).transfer(external("30"));

    assertThat(sagaService().onSettlementResult(t.id(), false, "unknown_institution"))
        .isEqualTo(Outcome.COMPENSATED);

    assertThat(t.status()).isEqualTo(TransferStatus.FAILED);
    assertThat(t.failureReason()).contains("unknown_institution");
    assertThat(ledger.balanceOf(alice.id())).isEqualTo(Money.of("100"));
    assertThat(ledger.balanceOf(SystemAccounts.SETTLEMENT_IN_TRANSIT)).isEqualTo(Money.ZERO);
    assertThat(events).extracting(DomainEvent::eventType).endsWith("TransferFailed");
    assertThat(sagaService().onSettlementResult(t.id(), false, "again"))
        .isEqualTo(Outcome.DUPLICATE);
  }

  @Test
  void overdueSagaIsCompensatedOnlyAfterGraceAndLateSettlementIsFlagged() {
    Transfer t = service(1).transfer(external("30"));

    clock.now = NOW.plusSeconds(14);
    assertThat(sagaService().compensateOverdue(10)).isZero();

    clock.now = NOW.plusSeconds(15);
    assertThat(sagaService().compensateOverdue(10)).isEqualTo(1);
    assertThat(t.failureReason()).contains(SettlementSaga.TIMEOUT_REASON);
    assertThat(ledger.balanceOf(alice.id())).isEqualTo(Money.of("100"));

    assertThat(sagaService().onSettlementResult(t.id(), true, null))
        .isEqualTo(Outcome.LATE_SETTLEMENT);
    assertThat(ledger.balanceOf(alice.id())).isEqualTo(Money.of("100"));
    assertThat(sagaService().compensateOverdue(10)).isZero();
  }

  @Test
  void resultForUnknownTransferIsReported() {
    assertThat(sagaService().onSettlementResult(TransferId.newId(), true, null))
        .isEqualTo(Outcome.UNKNOWN_TRANSFER);
  }

  @Test
  void externalTransferWithoutFundsStartsNoSaga() {
    assertThatThrownBy(() -> service(1).transfer(external("100.01")))
        .extracting("code")
        .isEqualTo("transfer.insufficient_funds");
    assertThat(sagaStore).isEmpty();
    assertThat(settlementRequests).isEmpty();
  }

  // ---------- limits ----------

  @Test
  void limitsAreCheckedWithTheInjectedClock() {
    journal.add(JournalEntry.deposit(JournalEntryId.newId(), alice.id(), Money.of("900"), NOW));
    customLimits.put(
        alice.id(), new TransferLimits(Money.of("50"), Money.of("80"), Money.of("30")));

    assertThatThrownBy(() -> service(1).transfer(internal(bob, "50.01")))
        .extracting("code")
        .isEqualTo("limit.per_transaction_exceeded");

    // NOW is 12:00 in Sao Paulo: daytime, only the daily limit applies.
    service(1).transfer(internal(bob, "50"));
    service(1).transfer(internal(bob, "30"));
    assertThatThrownBy(() -> service(1).transfer(internal(bob, "0.01")))
        .extracting("code")
        .isEqualTo("limit.daily_exceeded");

    // Next day 20:30 local (23:30Z): new daily window, but the nightly limit (30) applies.
    clock.now = Instant.parse("2026-11-03T23:30:00Z");
    service(1).transfer(internal(bob, "30"));
    assertThatThrownBy(() -> service(1).transfer(internal(bob, "0.01")))
        .extracting("code")
        .isEqualTo("limit.nightly_exceeded");

    // 01:00 local, after midnight: daily window reset, same night window -> still exceeded.
    clock.now = Instant.parse("2026-11-04T04:00:00Z");
    assertThatThrownBy(() -> service(1).transfer(internal(bob, "0.01")))
        .extracting("code")
        .isEqualTo("limit.nightly_exceeded");

    // 06:00 local: day again.
    clock.now = Instant.parse("2026-11-04T09:00:00Z");
    service(1).transfer(internal(bob, "50"));
  }
}
