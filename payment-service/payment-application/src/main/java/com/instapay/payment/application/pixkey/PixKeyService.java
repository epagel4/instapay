package com.instapay.payment.application.pixkey;

import com.instapay.payment.application.ResourceNotFoundException;
import com.instapay.payment.application.port.out.AccountRepository;
import com.instapay.payment.application.port.out.KeyClaimRepository;
import com.instapay.payment.application.port.out.PixKeyRepository;
import com.instapay.payment.application.port.out.TransactionRunner;
import com.instapay.payment.domain.account.Account;
import com.instapay.payment.domain.account.AccountId;
import com.instapay.payment.domain.pixkey.KeyClaim;
import com.instapay.payment.domain.pixkey.KeyClaimId;
import com.instapay.payment.domain.pixkey.KeyClaimStatus;
import com.instapay.payment.domain.pixkey.PixKey;
import com.instapay.payment.domain.pixkey.PixKeyRegistration;
import com.instapay.payment.domain.pixkey.PixKeyType;
import com.instapay.payment.domain.shared.DomainException;
import java.time.Clock;
import java.util.List;
import java.util.function.Consumer;

/**
 * Payment keys (DICT): registration, lookup, deletion and claims (portability / ownership).
 *
 * <p>Locking: account rows are locked when the per-account key limit is checked; key rows are
 * locked when a key is deleted, claimed or moved, so these operations serialize per key.
 */
public class PixKeyService {

  public record KeyOwner(PixKeyRegistration registration, Account account) {}

  private final AccountRepository accounts;
  private final PixKeyRepository keys;
  private final KeyClaimRepository claims;
  private final TransactionRunner tx;
  private final Clock clock;

  public PixKeyService(
      AccountRepository accounts,
      PixKeyRepository keys,
      KeyClaimRepository claims,
      TransactionRunner tx,
      Clock clock) {
    this.accounts = accounts;
    this.keys = keys;
    this.claims = claims;
    this.tx = tx;
    this.clock = clock;
  }

  /** Random keys are generated here; any client-supplied value for them is rejected. */
  public PixKeyRegistration register(AccountId accountId, PixKeyType type, String value) {
    PixKey key = toKey(type, value);
    return tx.inTransaction(
        () -> {
          Account account = lockAccount(accountId);
          PixKeyRegistration registration =
              PixKeyRegistration.register(
                  key, account, keys.countByAccount(accountId), clock.instant());
          keys.insert(registration);
          return registration;
        });
  }

  public KeyOwner lookup(PixKey key) {
    PixKeyRegistration registration = keys.find(key).orElseThrow(() -> keyNotFound(key));
    return new KeyOwner(registration, account(registration.account()));
  }

  public List<PixKeyRegistration> listByAccount(AccountId accountId) {
    account(accountId);
    return keys.listByAccount(accountId);
  }

  public void delete(PixKey key, AccountId actor) {
    tx.inTransaction(
        () -> {
          PixKeyRegistration registration =
              keys.findForUpdate(key).orElseThrow(() -> keyNotFound(key));
          registration.ensureOwnedBy(actor);
          if (claims.hasOpenClaim(key)) {
            throw new DomainException(
                "pixkey.claim_open", "Key has an open claim and cannot be deleted");
          }
          keys.delete(key);
          return null;
        });
  }

  public KeyClaim openClaim(AccountId claimerId, PixKey key) {
    return tx.inTransaction(
        () -> {
          PixKeyRegistration registration =
              keys.findForUpdate(key).orElseThrow(() -> keyNotFound(key));
          Account donor = account(registration.account());
          Account claimer = lockAccount(claimerId);
          KeyClaim claim =
              KeyClaim.open(
                  KeyClaimId.newId(),
                  registration,
                  donor,
                  claimer,
                  keys.countByAccount(claimerId),
                  clock.instant());
          claims.insert(claim);
          return claim;
        });
  }

  public KeyClaim getClaim(KeyClaimId id) {
    return claims.findById(id).orElseThrow(() -> claimNotFound(id));
  }

  public KeyClaim confirmClaim(KeyClaimId id, AccountId actor) {
    return resolve(id, claim -> claim.confirm(actor, clock.instant()));
  }

  public KeyClaim completeClaim(KeyClaimId id, AccountId actor) {
    return resolve(id, claim -> claim.complete(actor, clock.instant()));
  }

  public KeyClaim cancelClaim(KeyClaimId id, AccountId actor) {
    return resolve(id, claim -> claim.cancel(actor, clock.instant()));
  }

  /** Applies the transition and, if the claim completed, moves the key to the claimer. */
  private KeyClaim resolve(KeyClaimId id, Consumer<KeyClaim> transition) {
    return tx.inTransaction(
        () -> {
          KeyClaim claim = claims.findByIdForUpdate(id).orElseThrow(() -> claimNotFound(id));
          transition.accept(claim);
          if (claim.status() == KeyClaimStatus.COMPLETED) {
            PixKeyRegistration registration =
                keys.findForUpdate(claim.key()).orElseThrow(() -> keyNotFound(claim.key()));
            Account claimer = lockAccount(claim.claimer());
            // Re-checked at completion: the claimer may have registered keys since opening.
            PixKeyRegistration.ensureCanHoldAnotherKey(claimer, keys.countByAccount(claimer.id()));
            keys.update(registration.movedTo(claimer.id(), clock.instant()));
          }
          claims.update(claim);
          return claim;
        });
  }

  private static PixKey toKey(PixKeyType type, String value) {
    if (type == PixKeyType.RANDOM) {
      if (value != null) {
        throw new DomainException(
            "pixkey.random_value_not_allowed", "Random keys are generated by the server");
      }
      return PixKey.random();
    }
    if (value == null) {
      throw new DomainException("pixkey.invalid", "Key value is required");
    }
    return PixKey.of(type, value);
  }

  private Account account(AccountId id) {
    return accounts.findById(id).orElseThrow(() -> accountNotFound(id));
  }

  private Account lockAccount(AccountId id) {
    return accounts.findByIdForUpdate(id).orElseThrow(() -> accountNotFound(id));
  }

  private static ResourceNotFoundException accountNotFound(AccountId id) {
    return new ResourceNotFoundException("account.not_found", "Account " + id + " not found");
  }

  private static ResourceNotFoundException keyNotFound(PixKey key) {
    return new ResourceNotFoundException("pixkey.not_found", "Key " + key + " not found");
  }

  private static ResourceNotFoundException claimNotFound(KeyClaimId id) {
    return new ResourceNotFoundException("keyclaim.not_found", "Claim " + id + " not found");
  }
}
