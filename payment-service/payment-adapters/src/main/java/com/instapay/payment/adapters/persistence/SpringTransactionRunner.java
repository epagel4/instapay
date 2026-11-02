package com.instapay.payment.adapters.persistence;

import com.instapay.payment.application.port.out.TransactionRunner;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** NESTED: a new transaction when none is active, a savepoint inside an existing one. */
@Component
public class SpringTransactionRunner implements TransactionRunner {

  private final TransactionTemplate template;

  public SpringTransactionRunner(PlatformTransactionManager transactionManager) {
    this.template = new TransactionTemplate(transactionManager);
    this.template.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
  }

  @Override
  public <T> T inTransaction(Supplier<T> work) {
    return template.execute(status -> work.get());
  }
}
