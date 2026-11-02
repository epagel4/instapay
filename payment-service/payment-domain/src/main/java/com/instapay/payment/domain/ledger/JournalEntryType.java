package com.instapay.payment.domain.ledger;

public enum JournalEntryType {
  /** Money entering the platform from outside (cash-in). */
  DEPOSIT,
  /** Internal transfer, settled immediately. */
  TRANSFER,
  /** Customer debited into in-transit, pending settlement with another institution. */
  EXTERNAL_TRANSFER,
  /** In-transit moved to the settlement account once the clearing house settled. */
  SETTLEMENT,
  /** In-transit returned to the customer: settlement rejected or timed out. */
  COMPENSATION,
  /** Original creditor returning money to the original debtor (devolução). */
  REFUND
}
