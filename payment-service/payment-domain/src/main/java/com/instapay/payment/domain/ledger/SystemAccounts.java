package com.instapay.payment.domain.ledger;

import com.instapay.payment.domain.account.AccountId;
import java.util.UUID;

/** Internal ledger accounts that are counterparts of customer movements. May go negative. */
public final class SystemAccounts {

  /** Counterpart of every deposit: its balance is minus the total money brought in. */
  public static final AccountId FUNDING = new AccountId(new UUID(0, 1));

  /** Money debited from customers for transfers to other institutions, awaiting settlement. */
  public static final AccountId SETTLEMENT_IN_TRANSIT = new AccountId(new UUID(0, 2));

  /** Counterpart of money settled with other institutions: its balance is the net amount sent. */
  public static final AccountId SETTLEMENT = new AccountId(new UUID(0, 3));

  private SystemAccounts() {}
}
