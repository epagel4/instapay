package com.instapay.payment.application.port.out;

import com.instapay.payment.domain.account.AccountId;
import com.instapay.payment.domain.ledger.JournalEntry;
import com.instapay.payment.domain.shared.Money;

/** Append-only double-entry ledger. */
public interface LedgerRepository {

  /** Atomically stores the entry and all its postings. */
  void append(JournalEntry entry);

  /** Sum of every posting ever made to the account. */
  Money balanceOf(AccountId account);
}
