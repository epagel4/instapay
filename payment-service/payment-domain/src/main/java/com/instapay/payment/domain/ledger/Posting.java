package com.instapay.payment.domain.ledger;

import com.instapay.payment.domain.account.AccountId;
import com.instapay.payment.domain.shared.DomainException;
import com.instapay.payment.domain.shared.Money;
import java.util.Objects;

/**
 * One line of a journal entry. Signed amount: negative is a debit (value leaves the account),
 * positive is a credit (value enters it). An account's balance is the sum of its postings.
 */
public record Posting(AccountId account, Money amount) {

  public Posting {
    Objects.requireNonNull(account, "account");
    Objects.requireNonNull(amount, "amount");
    if (amount.isZero()) {
      throw new DomainException("ledger.posting.zero", "Posting amount cannot be zero");
    }
  }

  public boolean isDebit() {
    return amount.isNegative();
  }
}
