package com.instapay.payment.domain.shared;

import java.time.Instant;
import java.util.UUID;

/** Something that happened in the domain, recorded in the immutable audit log. */
public interface DomainEvent {

  String aggregateType();

  UUID aggregateId();

  String eventType();

  Instant occurredAt();
}
