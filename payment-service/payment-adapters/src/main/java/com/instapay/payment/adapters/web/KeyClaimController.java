package com.instapay.payment.adapters.web;

import com.instapay.payment.application.pixkey.PixKeyService;
import com.instapay.payment.domain.account.AccountId;
import com.instapay.payment.domain.pixkey.KeyClaim;
import com.instapay.payment.domain.pixkey.KeyClaimId;
import com.instapay.payment.domain.pixkey.PixKey;
import com.instapay.payment.domain.pixkey.PixKeyType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/key-claims")
class KeyClaimController {

  record OpenClaimRequest(
      @NotNull UUID claimerAccountId, @NotNull PixKeyType keyType, @NotBlank String keyValue) {}

  /** The account performing the action (donor or claimer). */
  record ClaimActionRequest(@NotNull UUID accountId) {}

  record ClaimResponse(
      UUID id,
      String keyType,
      String keyValue,
      String type,
      String status,
      UUID donorAccountId,
      UUID claimerAccountId,
      Instant openedAt,
      Instant resolutionDeadline,
      Instant resolvedAt) {

    static ClaimResponse of(KeyClaim c) {
      return new ClaimResponse(
          c.id().value(),
          c.key().type().name(),
          c.key().value(),
          c.type().name(),
          c.status().name(),
          c.donor().value(),
          c.claimer().value(),
          c.openedAt(),
          c.resolutionDeadline(),
          c.resolvedAt().orElse(null));
    }
  }

  private final PixKeyService keys;

  KeyClaimController(PixKeyService keys) {
    this.keys = keys;
  }

  @PostMapping
  ResponseEntity<ClaimResponse> open(@Valid @RequestBody OpenClaimRequest request) {
    KeyClaim claim =
        keys.openClaim(
            new AccountId(request.claimerAccountId()),
            PixKey.of(request.keyType(), request.keyValue()));
    return ResponseEntity.created(URI.create("/api/v1/key-claims/" + claim.id()))
        .body(ClaimResponse.of(claim));
  }

  @GetMapping("/{id}")
  ClaimResponse get(@PathVariable UUID id) {
    return ClaimResponse.of(keys.getClaim(new KeyClaimId(id)));
  }

  @PostMapping("/{id}/confirm")
  ClaimResponse confirm(@PathVariable UUID id, @Valid @RequestBody ClaimActionRequest request) {
    return ClaimResponse.of(
        keys.confirmClaim(new KeyClaimId(id), new AccountId(request.accountId())));
  }

  @PostMapping("/{id}/cancel")
  ClaimResponse cancel(@PathVariable UUID id, @Valid @RequestBody ClaimActionRequest request) {
    return ClaimResponse.of(
        keys.cancelClaim(new KeyClaimId(id), new AccountId(request.accountId())));
  }

  @PostMapping("/{id}/complete")
  ClaimResponse complete(@PathVariable UUID id, @Valid @RequestBody ClaimActionRequest request) {
    return ClaimResponse.of(
        keys.completeClaim(new KeyClaimId(id), new AccountId(request.accountId())));
  }
}
