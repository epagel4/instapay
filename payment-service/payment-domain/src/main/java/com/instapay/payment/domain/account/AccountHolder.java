package com.instapay.payment.domain.account;

import com.instapay.payment.domain.shared.Cpf;
import com.instapay.payment.domain.shared.DomainException;
import java.util.Objects;

public record AccountHolder(String name, Cpf cpf) {

  private static final int MAX_NAME_LENGTH = 140;

  public AccountHolder {
    Objects.requireNonNull(cpf, "cpf");
    name = name == null ? "" : name.strip();
    if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
      throw new DomainException("holder.name.invalid", "Holder name must have 1-140 characters");
    }
  }
}
