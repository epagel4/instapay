package com.instapay.payment.adapters;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "instapay.locking.strategy=pessimistic")
class PessimisticLockingConcurrencyTest extends TransferConcurrencyTest {

  /** Debits queue on the row lock: an exact, deterministic result. */
  @Override
  void assertScarceFunds(Outcome o) {
    assertThat(o.succeeded()).isEqualTo(50);
    assertThat(o.insufficientFunds()).isEqualTo(50);
    assertThat(o.conflicts()).isZero();
  }

  @Override
  void assertAmpleFunds(Outcome o) {
    assertThat(o.succeeded()).isEqualTo(100);
    assertThat(o.conflicts()).isZero();
  }
}
