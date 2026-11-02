package com.instapay.payment.domain.pixkey;

import com.instapay.payment.domain.account.Account;
import com.instapay.payment.domain.account.AccountId;
import com.instapay.payment.domain.account.AccountStatus;
import com.instapay.payment.domain.shared.DomainException;
import java.time.Instant;
import java.util.Objects;

/** A key bound to an account. Uniqueness of the key itself is enforced by the repository. */
public record PixKeyRegistration(PixKey key, AccountId account, Instant createdAt) {

  /** DICT limit for individuals (pessoa física). */
  public static final int MAX_KEYS_PER_ACCOUNT = 5;

  public PixKeyRegistration {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(account, "account");
    Objects.requireNonNull(createdAt, "createdAt");
  }

  public static PixKeyRegistration register(
      PixKey key, Account account, int keysAlreadyOnAccount, Instant now) {
    ensureCanHoldAnotherKey(account, keysAlreadyOnAccount);
    if (key.type() == PixKeyType.CPF && !key.value().equals(account.holder().cpf().value())) {
      throw new DomainException("pixkey.cpf_mismatch", "CPF key must be the account holder's CPF");
    }
    return new PixKeyRegistration(key, account.id(), now);
  }

  public static void ensureCanHoldAnotherKey(Account account, int keysAlreadyOnAccount) {
    if (account.status() != AccountStatus.ACTIVE) {
      throw new DomainException("pixkey.account_not_active", "Only active accounts can hold keys");
    }
    if (keysAlreadyOnAccount >= MAX_KEYS_PER_ACCOUNT) {
      throw new DomainException(
          "pixkey.limit_reached", "Account already has " + MAX_KEYS_PER_ACCOUNT + " keys");
    }
  }

  public void ensureOwnedBy(AccountId actor) {
    if (!account.equals(actor)) {
      throw new DomainException("pixkey.not_owner", "Key does not belong to this account");
    }
  }

  public PixKeyRegistration movedTo(AccountId newAccount, Instant now) {
    return new PixKeyRegistration(key, newAccount, now);
  }
}
