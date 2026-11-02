package com.instapay.contracts;

public final class Topics {

  /** payment-service -> settlement-service. Key: transferId. */
  public static final String SETTLEMENT_REQUESTS = "settlement.requests";

  /** settlement-service -> payment-service. Key: transferId. */
  public static final String SETTLEMENT_RESULTS = "settlement.results";

  private Topics() {}
}
