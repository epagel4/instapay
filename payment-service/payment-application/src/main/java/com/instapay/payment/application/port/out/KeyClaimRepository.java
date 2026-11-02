package com.instapay.payment.application.port.out;

import com.instapay.payment.domain.pixkey.KeyClaim;
import com.instapay.payment.domain.pixkey.KeyClaimId;
import com.instapay.payment.domain.pixkey.PixKey;
import java.util.Optional;

public interface KeyClaimRepository {

  /** Throws a DomainException "keyclaim.already_open" if the key already has an open claim. */
  void insert(KeyClaim claim);

  Optional<KeyClaim> findById(KeyClaimId id);

  Optional<KeyClaim> findByIdForUpdate(KeyClaimId id);

  boolean hasOpenClaim(PixKey key);

  void update(KeyClaim claim);
}
