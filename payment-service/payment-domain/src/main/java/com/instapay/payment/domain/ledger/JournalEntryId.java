package com.instapay.payment.domain.ledger;

import java.util.Objects;
import java.util.UUID;

public record JournalEntryId(UUID value) {

  public JournalEntryId {
    Objects.requireNonNull(value, "journalEntryId");
  }

  public static JournalEntryId newId() {
    return new JournalEntryId(UUID.randomUUID());
  }
}
