package com.instapay.payment.domain.account;

public enum AccountStatus {
  /** Can send and receive. */
  ACTIVE,
  /** Precautionary block: can receive, cannot send. */
  BLOCKED,
  /** Terminal: no movement at all. */
  CLOSED
}
