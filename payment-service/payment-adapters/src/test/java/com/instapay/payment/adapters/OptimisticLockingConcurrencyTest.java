package com.instapay.payment.adapters;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "instapay.locking.strategy=optimistic")
class OptimisticLockingConcurrencyTest extends TransferConcurrencyTest {

  /**
   * Safety holds (checked in the base class), but outcomes depend on timing: some requests may
   * exhaust their retries and get 409 even though funds were available.
   */
  @Override
  void assertScarceFunds(Outcome o) {
    assertThat(o.succeeded()).isPositive().isLessThanOrEqualTo(50);
    assertThat(o.succeeded() + o.insufficientFunds() + o.conflicts()).isEqualTo(100);
    assertThat(o.lockConflicts()).isPositive();
  }

  @Override
  void assertAmpleFunds(Outcome o) {
    assertThat(o.succeeded() + o.conflicts()).isEqualTo(100);
    assertThat(o.insufficientFunds()).isZero();
    assertThat(o.lockConflicts()).isPositive();
  }
}
