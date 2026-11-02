package com.instapay.payment.domain.pixkey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.instapay.payment.domain.account.Account;
import com.instapay.payment.domain.account.AccountHolder;
import com.instapay.payment.domain.account.AccountId;
import com.instapay.payment.domain.shared.Cpf;
import com.instapay.payment.domain.shared.Ispb;
import com.instapay.payment.domain.shared.Money;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PixKeyRegistrationTest {

  static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
  static final Ispb BANK = new Ispb("00000001");
  static final Cpf MARIA = Cpf.of("52998224725");

  final Account account = Account.open(AccountId.newId(), BANK, new AccountHolder("Maria", MARIA));
  final PixKey email = PixKey.of(PixKeyType.EMAIL, "maria@example.com");

  @Test
  void registersKeyOnActiveAccount() {
    PixKeyRegistration reg = PixKeyRegistration.register(email, account, 0, NOW);

    assertThat(reg.key()).isEqualTo(email);
    assertThat(reg.account()).isEqualTo(account.id());
    assertThat(reg.createdAt()).isEqualTo(NOW);
  }

  @Test
  void cpfKeyMustBeHoldersCpf() {
    assertThatCode(
            () ->
                PixKeyRegistration.register(
                    PixKey.of(PixKeyType.CPF, MARIA.value()), account, 0, NOW))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () ->
                PixKeyRegistration.register(
                    PixKey.of(PixKeyType.CPF, "11144477735"), account, 0, NOW))
        .extracting("code")
        .isEqualTo("pixkey.cpf_mismatch");
  }

  @Test
  void enforcesPerAccountLimit() {
    assertThatCode(() -> PixKeyRegistration.register(email, account, 4, NOW))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> PixKeyRegistration.register(email, account, 5, NOW))
        .extracting("code")
        .isEqualTo("pixkey.limit_reached");
  }

  @Test
  void onlyActiveAccountsHoldKeys() {
    account.block();
    assertThatThrownBy(() -> PixKeyRegistration.register(email, account, 0, NOW))
        .extracting("code")
        .isEqualTo("pixkey.account_not_active");
    account.unblock();
    account.close(Money.ZERO);
    assertThatThrownBy(() -> PixKeyRegistration.register(email, account, 0, NOW))
        .extracting("code")
        .isEqualTo("pixkey.account_not_active");
  }

  @Test
  void ownershipAndMove() {
    PixKeyRegistration reg = PixKeyRegistration.register(email, account, 0, NOW);
    AccountId other = AccountId.newId();

    assertThatCode(() -> reg.ensureOwnedBy(account.id())).doesNotThrowAnyException();
    assertThatThrownBy(() -> reg.ensureOwnedBy(other))
        .extracting("code")
        .isEqualTo("pixkey.not_owner");

    PixKeyRegistration moved = reg.movedTo(other, NOW.plusSeconds(60));
    assertThat(moved.account()).isEqualTo(other);
    assertThat(moved.key()).isEqualTo(email);
    assertThat(moved.createdAt()).isEqualTo(NOW.plusSeconds(60));
  }
}
