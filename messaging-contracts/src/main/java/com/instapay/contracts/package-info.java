/**
 * Versioned Kafka message contracts shared by payment-service and settlement-service.
 *
 * <p>Plain records only: no business rules and no framework dependencies. Only messaging adapters
 * may depend on this module (enforced by ArchUnit in each service).
 */
package com.instapay.contracts;
