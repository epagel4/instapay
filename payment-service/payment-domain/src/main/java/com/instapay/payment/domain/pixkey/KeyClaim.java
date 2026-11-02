package com.instapay.payment.domain.pixkey;

import com.instapay.payment.domain.account.Account;
import com.instapay.payment.domain.account.AccountId;
import com.instapay.payment.domain.shared.DomainException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Request to move a registered key from the donor account to the claimer account. The type is
 * derived, not chosen: same holder CPF means portability, a different holder means ownership.
 *
 * <p>shortcut: portability claims never expire (the real DICT cancels them after 7 days without
 * donor confirmation); would need a scheduled job.
 */
public final class KeyClaim {

  public static final Duration RESOLUTION_PERIOD = Duration.ofDays(7);

  private final KeyClaimId id;
  private final PixKey key;
  private final KeyClaimType type;
  private final AccountId donor;
  private final AccountId claimer;
  private final Instant openedAt;
  private final Instant resolutionDeadline;
  private KeyClaimStatus status;
  private Instant resolvedAt;

  private KeyClaim(
      KeyClaimId id,
      PixKey key,
      KeyClaimType type,
      AccountId donor,
      AccountId claimer,
      Instant openedAt,
      Instant resolutionDeadline,
      KeyClaimStatus status,
      Instant resolvedAt) {
    this.id = Objects.requireNonNull(id, "id");
    this.key = Objects.requireNonNull(key, "key");
    this.type = Objects.requireNonNull(type, "type");
    this.donor = Objects.requireNonNull(donor, "donor");
    this.claimer = Objects.requireNonNull(claimer, "claimer");
    this.openedAt = Objects.requireNonNull(openedAt, "openedAt");
    this.resolutionDeadline = Objects.requireNonNull(resolutionDeadline, "resolutionDeadline");
    this.status = Objects.requireNonNull(status, "status");
    this.resolvedAt = resolvedAt;
  }

  public static KeyClaim open(
      KeyClaimId id,
      PixKeyRegistration registration,
      Account donor,
      Account claimer,
      int keysAlreadyOnClaimer,
      Instant now) {
    if (!registration.account().equals(donor.id())) {
      throw new IllegalArgumentException("Donor must be the account holding the key");
    }
    if (claimer.id().equals(donor.id())) {
      throw new DomainException("keyclaim.already_owner", "Key already belongs to this account");
    }
    PixKeyRegistration.ensureCanHoldAnotherKey(claimer, keysAlreadyOnClaimer);
    KeyClaimType type =
        donor.holder().cpf().equals(claimer.holder().cpf())
            ? KeyClaimType.PORTABILITY
            : KeyClaimType.OWNERSHIP;
    PixKeyType keyType = registration.key().type();
    if (type == KeyClaimType.OWNERSHIP
        && keyType != PixKeyType.EMAIL
        && keyType != PixKeyType.PHONE) {
      throw new DomainException(
          "keyclaim.ownership_not_allowed",
          "Only e-mail and phone keys can be claimed by another holder");
    }
    return new KeyClaim(
        id,
        registration.key(),
        type,
        donor.id(),
        claimer.id(),
        now,
        now.plus(RESOLUTION_PERIOD),
        KeyClaimStatus.OPEN,
        null);
  }

  /** Rehydrates persisted state; no transition rules applied. */
  public static KeyClaim restore(
      KeyClaimId id,
      PixKey key,
      KeyClaimType type,
      AccountId donor,
      AccountId claimer,
      Instant openedAt,
      Instant resolutionDeadline,
      KeyClaimStatus status,
      Instant resolvedAt) {
    return new KeyClaim(
        id, key, type, donor, claimer, openedAt, resolutionDeadline, status, resolvedAt);
  }

  /** Donor agrees to give the key up (either claim type). */
  public void confirm(AccountId actor, Instant now) {
    requireOpen();
    requireActor(donor, actor);
    resolve(KeyClaimStatus.COMPLETED, now);
  }

  /** Either side withdraws; for ownership claims, the donor cancelling means contesting. */
  public void cancel(AccountId actor, Instant now) {
    requireOpen();
    if (!actor.equals(donor) && !actor.equals(claimer)) {
      throw new DomainException("keyclaim.not_participant", "Account is not part of this claim");
    }
    resolve(KeyClaimStatus.CANCELLED, now);
  }

  /** Claimer takes an uncontested ownership claim once the resolution period is over. */
  public void complete(AccountId actor, Instant now) {
    requireOpen();
    requireActor(claimer, actor);
    if (type != KeyClaimType.OWNERSHIP) {
      throw new DomainException(
          "keyclaim.requires_donor_confirmation",
          "Portability completes only on donor confirmation");
    }
    if (now.isBefore(resolutionDeadline)) {
      throw new DomainException(
          "keyclaim.resolution_period_running", "Donor can contest until " + resolutionDeadline);
    }
    resolve(KeyClaimStatus.COMPLETED, now);
  }

  private void requireOpen() {
    if (status != KeyClaimStatus.OPEN) {
      throw new DomainException("keyclaim.not_open", "Claim " + id + " is already " + status);
    }
  }

  private static void requireActor(AccountId expected, AccountId actor) {
    if (!expected.equals(actor)) {
      throw new DomainException("keyclaim.not_allowed", "Account cannot perform this action");
    }
  }

  private void resolve(KeyClaimStatus newStatus, Instant now) {
    status = newStatus;
    resolvedAt = Objects.requireNonNull(now, "now");
  }

  public KeyClaimId id() {
    return id;
  }

  public PixKey key() {
    return key;
  }

  public KeyClaimType type() {
    return type;
  }

  public AccountId donor() {
    return donor;
  }

  public AccountId claimer() {
    return claimer;
  }

  public Instant openedAt() {
    return openedAt;
  }

  public Instant resolutionDeadline() {
    return resolutionDeadline;
  }

  public KeyClaimStatus status() {
    return status;
  }

  public Optional<Instant> resolvedAt() {
    return Optional.ofNullable(resolvedAt);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof KeyClaim other && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return id.hashCode();
  }
}
