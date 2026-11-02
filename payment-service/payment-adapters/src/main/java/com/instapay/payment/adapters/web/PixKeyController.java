package com.instapay.payment.adapters.web;

import com.instapay.payment.application.pixkey.PixKeyService;
import com.instapay.payment.application.pixkey.PixKeyService.KeyOwner;
import com.instapay.payment.domain.account.AccountId;
import com.instapay.payment.domain.pixkey.PixKey;
import com.instapay.payment.domain.pixkey.PixKeyRegistration;
import com.instapay.payment.domain.pixkey.PixKeyType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PixKeyController {

  record RegisterKeyRequest(@NotNull UUID accountId, @NotNull PixKeyType type, String value) {}

  record KeyResponse(String type, String value, UUID accountId, Instant createdAt) {

    static KeyResponse of(PixKeyRegistration r) {
      return new KeyResponse(
          r.key().type().name(), r.key().value(), r.account().value(), r.createdAt());
    }
  }

  /** What a payer sees before confirming: enough to recognize the payee, CPF masked. */
  record KeyLookupResponse(
      String type,
      String value,
      UUID accountId,
      String institution,
      String holderName,
      String holderCpf) {

    static KeyLookupResponse of(KeyOwner owner) {
      PixKey key = owner.registration().key();
      return new KeyLookupResponse(
          key.type().name(),
          key.value(),
          owner.account().id().value(),
          owner.account().institution().value(),
          owner.account().holder().name(),
          owner.account().holder().cpf().toString());
    }
  }

  private final PixKeyService keys;

  PixKeyController(PixKeyService keys) {
    this.keys = keys;
  }

  @PostMapping("/api/v1/keys")
  ResponseEntity<KeyResponse> register(@Valid @RequestBody RegisterKeyRequest request) {
    PixKeyRegistration r =
        keys.register(new AccountId(request.accountId()), request.type(), request.value());
    return ResponseEntity.created(
            URI.create("/api/v1/keys/" + r.key().type() + "/" + r.key().value()))
        .body(KeyResponse.of(r));
  }

  // shortcut: lookups are unthrottled; the real DICT rate-limits them to prevent data scraping.
  @GetMapping("/api/v1/keys/{type}/{value}")
  KeyLookupResponse lookup(@PathVariable PixKeyType type, @PathVariable String value) {
    return KeyLookupResponse.of(keys.lookup(PixKey.of(type, value)));
  }

  @DeleteMapping("/api/v1/keys/{type}/{value}")
  ResponseEntity<Void> delete(
      @PathVariable PixKeyType type, @PathVariable String value, @RequestParam UUID accountId) {
    keys.delete(PixKey.of(type, value), new AccountId(accountId));
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/api/v1/accounts/{accountId}/keys")
  List<KeyResponse> listByAccount(@PathVariable UUID accountId) {
    return keys.listByAccount(new AccountId(accountId)).stream().map(KeyResponse::of).toList();
  }
}
