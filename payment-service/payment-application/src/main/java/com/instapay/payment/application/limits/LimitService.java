package com.instapay.payment.application.limits;

import com.instapay.payment.application.ResourceNotFoundException;
import com.instapay.payment.application.port.out.AccountLimitsRepository;
import com.instapay.payment.application.port.out.AccountRepository;
import com.instapay.payment.application.port.out.TransactionRunner;
import com.instapay.payment.application.port.out.TransferRepository;
import com.instapay.payment.domain.account.AccountId;
import com.instapay.payment.domain.limits.LimitWindows;
import com.instapay.payment.domain.limits.TransferLimits;
import com.instapay.payment.domain.shared.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

/**
 * Transfer limits per account. Usage is derived from the transfer table (no counters to drift): the
 * debtor's row lock (ADR-002) makes check-then-insert safe.
 *
 * <p>shortcut: limit increases apply immediately; Pix requires a 24-48 h delay for increases
 * (decreases are immediate). Would need a pending-change record with an effective time.
 */
public class LimitService {

  public record Usage(
      TransferLimits limits, boolean custom, Money spentToday, Optional<Money> spentTonight) {}

  private final AccountLimitsRepository limits;
  private final AccountRepository accounts;
  private final TransferRepository transfers;
  private final TransactionRunner tx;
  private final Clock clock;
  private final TransferLimits defaults;
  private final ZoneId zone;

  public LimitService(
      AccountLimitsRepository limits,
      AccountRepository accounts,
      TransferRepository transfers,
      TransactionRunner tx,
      Clock clock,
      TransferLimits defaults,
      ZoneId zone) {
    this.limits = limits;
    this.accounts = accounts;
    this.transfers = transfers;
    this.tx = tx;
    this.clock = clock;
    this.defaults = defaults;
    this.zone = zone;
  }

  /** Call with the debtor locked, inside the transfer's transaction. */
  public void check(AccountId debtor, Money amount, Instant now) {
    LimitWindows windows = LimitWindows.at(now, zone);
    effective(debtor)
        .check(amount, spent(debtor, windows.day()), windows.night().map(n -> spent(debtor, n)));
  }

  public Usage usage(AccountId account) {
    requireAccount(account);
    LimitWindows windows = LimitWindows.at(clock.instant(), zone);
    Optional<TransferLimits> custom = limits.find(account);
    return new Usage(
        custom.orElse(defaults),
        custom.isPresent(),
        spent(account, windows.day()),
        windows.night().map(n -> spent(account, n)));
  }

  public Usage update(AccountId account, TransferLimits newLimits) {
    tx.inTransaction(
        () -> {
          requireAccount(account);
          limits.save(account, newLimits);
          return null;
        });
    return usage(account);
  }

  private TransferLimits effective(AccountId account) {
    return limits.find(account).orElse(defaults);
  }

  private Money spent(AccountId account, LimitWindows.Window window) {
    return transfers.sumOutgoing(account, window.start(), window.end());
  }

  private void requireAccount(AccountId id) {
    accounts
        .findById(id)
        .orElseThrow(
            () ->
                new ResourceNotFoundException("account.not_found", "Account " + id + " not found"));
  }
}
