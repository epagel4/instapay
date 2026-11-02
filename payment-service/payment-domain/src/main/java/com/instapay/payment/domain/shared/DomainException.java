package com.instapay.payment.domain.shared;

/**
 * Violation of a domain invariant. The {@code code} is stable and machine-readable; adapters map it
 * to an RFC 7807 problem type.
 */
public class DomainException extends RuntimeException {

  private final String code;

  public DomainException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
