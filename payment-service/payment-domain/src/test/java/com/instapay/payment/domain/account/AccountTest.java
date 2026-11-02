package com.instapay.payment.domain.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.instapay.payment.domain.shared.Cpf;
import com.instapay.payment.domain.shared.DomainException;
import com.instapay.payment.domain.shared.Ispb;
import com.instapay.payment.domain.shared.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class AccountTest {

  static final Ispb INSTAPAY = new Ispb("00000001");
  static final AccountHolder HOLDER = new AccountHolder("Maria Silva", Cpf.of("52998224725"));

  private final Account account = Account.open(AccountId.newId(), INSTAPAY, HOLDER);

  @Test
  void opensActiveAndMovesFreely() {
    assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
    assertThat(account.version()).isZero();
    assertThatCode(account::ensureCanDebit).doesNotThrowAnyException();
    assertThatCode(account::ensureCanCredit).doesNotThrowAnyException();
    assertThat(account.ref()).isEqualTo(new AccountRef(INSTAPAY, account.id()));
    assertThat(account.institution()).isEqualTo(INSTAPAY);
    assertThat(account.holder()).isEqualTo(HOLDER);
  }

  @Test
  void blockedAccountReceivesButCannotSend() {
    account.block();

    assertThat(account.status()).isEqualTo(AccountStatus.BLOCKED);
    assertThatCode(account::ensureCanCredit).doesNotThrowAnyException();
    assertThatThrownBy(account::ensureCanDebit)
        .extracting("code")
        .isEqualTo("account.debit.not_allowed");

    account.unblock();
    assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
  }

  @Test
  void rejectsInvalidStatusTransitions() {
    assertThatThrownBy(account::unblock).extracting("code").isEqualTo("account.transition.invalid");
    account.block();
    assertThatThrownBy(account::block).extracting("code").isEqualTo("account.transition.invalid");
  }

  @Test
  void closesOnlyWithZeroBalanceAndThenAllowsNoMovement() {
    assertThatThrownBy(() -> account.close(Money.of("0.01")))
        .extracting("code")
        .isEqualTo("account.balance.not_zero");

    account.close(Money.ZERO);

    assertThat(account.status()).isEqualTo(AccountStatus.CLOSED);
    assertThatThrownBy(account::ensureCanDebit).isInstanceOf(DomainException.class);
    assertThatThrownBy(account::ensureCanCredit)
        .extracting("code")
        .isEqualTo("account.credit.not_allowed");
    assertThatThrownBy(() -> account.close(Money.ZERO))
        .extracting("code")
        .isEqualTo("account.closed");
  }

  @Test
  void restoreKeepsPersistedStateAndEqualityIsById() {
    Account restored = Account.restore(account.id(), INSTAPAY, HOLDER, AccountStatus.BLOCKED, 7);

    assertThat(restored.status()).isEqualTo(AccountStatus.BLOCKED);
    assertThat(restored.version()).isEqualTo(7);
    assertThat(restored).isEqualTo(account).hasSameHashCodeAs(account);
    assertThat(restored).isNotEqualTo(Account.open(AccountId.newId(), INSTAPAY, HOLDER));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   "})
  void holderNeedsName(String name) {
    assertThatThrownBy(() -> new AccountHolder(name, HOLDER.cpf()))
        .extracting("code")
        .isEqualTo("holder.name.invalid");
  }

  @Test
  void holderNameIsTrimmedAndBounded() {
    assertThat(new AccountHolder("  Ana  ", HOLDER.cpf()).name()).isEqualTo("Ana");
    assertThatThrownBy(() -> new AccountHolder("a".repeat(141), HOLDER.cpf()))
        .isInstanceOf(DomainException.class);
  }

  @Test
  void accountIdParsesUuid() {
    AccountId id = AccountId.newId();

    assertThat(AccountId.of(id.toString())).isEqualTo(id);
  }
}
