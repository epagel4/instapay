package com.instapay.payment.application.port.out;

import java.util.function.Supplier;

/**
 * Runs work atomically. If a transaction is already active, the work runs in a nested one
 * (savepoint): a failure undoes only this work, not the enclosing transaction.
 */
public interface TransactionRunner {

  <T> T inTransaction(Supplier<T> work);
}
