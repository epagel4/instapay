package com.instapay.payment.adapters.web;

import com.instapay.payment.application.account.AccountService;
import com.instapay.payment.application.account.AccountService.AccountWithBalance;
import com.instapay.payment.domain.account.Account;
import com.instapay.payment.domain.account.AccountId;
import com.instapay.payment.domain.shared.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
class AccountController {

  record OpenAccountRequest(@NotBlank String holderName, @NotBlank String holderCpf) {}

  record DepositRequest(@NotBlank String amount) {}

  record AccountResponse(
      UUID id,
      String institution,
      String holderName,
      String holderCpf,
      String status,
      String balance) {

    static AccountResponse of(Account a, Money balance) {
      return new AccountResponse(
          a.id().value(),
          a.institution().value(),
          a.holder().name(),
          a.holder().cpf().toString(), // masked
          a.status().name(),
          balance.toString());
    }
  }

  record DepositResponse(UUID journalEntryId, String balance) {}

  private final AccountService accounts;

  AccountController(AccountService accounts) {
    this.accounts = accounts;
  }

  @PostMapping
  ResponseEntity<AccountResponse> open(@Valid @RequestBody OpenAccountRequest request) {
    Account account = accounts.open(request.holderName(), request.holderCpf());
    return ResponseEntity.created(URI.create("/api/v1/accounts/" + account.id()))
        .body(AccountResponse.of(account, Money.ZERO));
  }

  @GetMapping("/{id}")
  AccountResponse get(@PathVariable UUID id) {
    AccountWithBalance found = accounts.get(new AccountId(id));
    return AccountResponse.of(found.account(), found.balance());
  }

  @PostMapping("/{id}/deposits")
  ResponseEntity<DepositResponse> deposit(
      @PathVariable UUID id, @Valid @RequestBody DepositRequest request) {
    var result = accounts.deposit(new AccountId(id), Money.of(request.amount()));
    return ResponseEntity.status(201)
        .body(new DepositResponse(result.journalEntryId().value(), result.balance().toString()));
  }
}
