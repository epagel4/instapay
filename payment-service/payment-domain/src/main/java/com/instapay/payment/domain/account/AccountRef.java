package com.instapay.payment.domain.account;

import com.instapay.payment.domain.shared.Ispb;
import java.util.Objects;

/** Fully qualified account: the institution that holds it plus the account id. */
public record AccountRef(Ispb institution, AccountId account) {

  public AccountRef {
    Objects.requireNonNull(institution, "institution");
    Objects.requireNonNull(account, "account");
  }
}
