package com.instapay.payment.application.port.out;

import com.instapay.payment.domain.shared.DomainEvent;

/** Append-only record of domain events, written in the same transaction as the change. */
public interface AuditLog {

  void append(DomainEvent event);
}
