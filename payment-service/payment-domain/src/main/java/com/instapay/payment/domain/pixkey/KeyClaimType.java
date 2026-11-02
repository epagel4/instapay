package com.instapay.payment.domain.pixkey;

public enum KeyClaimType {
  /** Same holder moving the key to another account. Donor must confirm. */
  PORTABILITY,
  /**
   * Different holder claiming an e-mail/phone they now own. Completes if the donor does not
   * contest.
   */
  OWNERSHIP
}
