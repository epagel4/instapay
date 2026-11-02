package com.instapay.payment.application.port.out;

import com.instapay.payment.domain.account.AccountId;
import com.instapay.payment.domain.pixkey.PixKey;
import com.instapay.payment.domain.pixkey.PixKeyRegistration;
import java.util.List;
import java.util.Optional;

public interface PixKeyRepository {

  /** Throws a DomainException "pixkey.already_registered" if the key exists (any account). */
  void insert(PixKeyRegistration registration);

  Optional<PixKeyRegistration> find(PixKey key);

  /** Same as find, holding a row lock until commit. */
  Optional<PixKeyRegistration> findForUpdate(PixKey key);

  List<PixKeyRegistration> listByAccount(AccountId account);

  int countByAccount(AccountId account);

  void update(PixKeyRegistration registration);

  void delete(PixKey key);
}
