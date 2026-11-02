package com.instapay.payment.adapters.web;

import com.instapay.payment.application.limits.LimitService;
import com.instapay.payment.application.limits.LimitService.Usage;
import com.instapay.payment.domain.account.AccountId;
import com.instapay.payment.domain.limits.TransferLimits;
import com.instapay.payment.domain.shared.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts/{accountId}/limits")
class LimitController {

  record LimitsRequest(
      @NotBlank String perTransaction, @NotBlank String daily, @NotBlank String nightly) {}

  /** spentTonight is null outside the night window (20h-6h, America/Sao_Paulo). */
  record LimitsResponse(
      String perTransaction,
      String daily,
      String nightly,
      boolean custom,
      String spentToday,
      String spentTonight) {

    static LimitsResponse of(Usage u) {
      return new LimitsResponse(
          u.limits().perTransaction().toString(),
          u.limits().daily().toString(),
          u.limits().nightly().toString(),
          u.custom(),
          u.spentToday().toString(),
          u.spentTonight().map(Money::toString).orElse(null));
    }
  }

  private final LimitService limits;

  LimitController(LimitService limits) {
    this.limits = limits;
  }

  @GetMapping
  LimitsResponse get(@PathVariable UUID accountId) {
    return LimitsResponse.of(limits.usage(new AccountId(accountId)));
  }

  @PutMapping
  LimitsResponse update(@PathVariable UUID accountId, @Valid @RequestBody LimitsRequest r) {
    TransferLimits newLimits =
        new TransferLimits(
            Money.of(r.perTransaction()), Money.of(r.daily()), Money.of(r.nightly()));
    return LimitsResponse.of(limits.update(new AccountId(accountId), newLimits));
  }
}
