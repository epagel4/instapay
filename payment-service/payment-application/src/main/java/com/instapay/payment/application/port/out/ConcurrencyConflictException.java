package com.instapay.payment.application.port.out;

/** A concurrent writer changed the aggregate first; the whole attempt must be retried. */
public class ConcurrencyConflictException extends RuntimeException {

  public ConcurrencyConflictException(String message) {
    super(message);
  }
}
