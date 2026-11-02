package com.instapay.payment.adapters.web;

import com.instapay.payment.application.pixkey.PixKeyService;
import com.instapay.payment.application.transfer.TransferService;
import com.instapay.payment.application.transfer.TransferService.TransferCommand;
import com.instapay.payment.domain.account.AccountId;
import com.instapay.payment.domain.account.AccountRef;
import com.instapay.payment.domain.pixkey.PixKey;
import com.instapay.payment.domain.pixkey.PixKeyType;
import com.instapay.payment.domain.shared.DomainException;
import com.instapay.payment.domain.shared.Ispb;
import com.instapay.payment.domain.shared.Money;
import com.instapay.payment.domain.transfer.Transfer;
import com.instapay.payment.domain.transfer.TransferId;
import com.instapay.payment.domain.transfer.TransferStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transfers")
class TransferController {

  /** Account in another (or this) institution. */
  record CreditorRequest(@NotBlank String ispb, @NotNull UUID accountId) {}

  record CreditorKeyRequest(@NotNull PixKeyType type, @NotBlank String value) {}

  /** Exactly one of creditorAccountId (this institution), creditor, creditorKey. */
  record TransferRequest(
      @NotNull UUID debtorAccountId,
      UUID creditorAccountId,
      @Valid CreditorRequest creditor,
      @Valid CreditorKeyRequest creditorKey,
      @NotBlank String amount) {}

  record TransferResponse(
      UUID id,
      UUID debtorAccountId,
      String debtorInstitution,
      UUID creditorAccountId,
      String creditorInstitution,
      String amount,
      String status,
      String failureReason,
      Instant createdAt,
      Instant finishedAt) {

    static TransferResponse of(Transfer t) {
      return new TransferResponse(
          t.id().value(),
          t.debtor().account().value(),
          t.debtor().institution().value(),
          t.creditor().account().value(),
          t.creditor().institution().value(),
          t.amount().toString(),
          t.status().name(),
          t.failureReason().orElse(null),
          t.createdAt(),
          t.finishedAt().orElse(null));
    }
  }

  private final TransferService transfers;
  private final PixKeyService keys;
  private final Ispb institution;

  TransferController(
      TransferService transfers,
      PixKeyService keys,
      @Value("${instapay.institution-ispb}") String institution) {
    this.transfers = transfers;
    this.keys = keys;
    this.institution = new Ispb(institution);
  }

  /**
   * 201 when settled synchronously (internal); 202 while settlement with another bank is pending.
   */
  @PostMapping
  ResponseEntity<TransferResponse> transfer(@Valid @RequestBody TransferRequest request) {
    Transfer transfer =
        transfers.transfer(
            new TransferCommand(
                new AccountId(request.debtorAccountId()),
                creditor(request),
                Money.of(request.amount())));
    HttpStatus status =
        transfer.status() == TransferStatus.PENDING ? HttpStatus.ACCEPTED : HttpStatus.CREATED;
    return ResponseEntity.status(status)
        .location(URI.create("/api/v1/transfers/" + transfer.id()))
        .body(TransferResponse.of(transfer));
  }

  /** Keys resolve against this institution's directory only (ADR-005, option A). */
  private AccountRef creditor(TransferRequest r) {
    int given =
        (r.creditorAccountId() != null ? 1 : 0)
            + (r.creditor() != null ? 1 : 0)
            + (r.creditorKey() != null ? 1 : 0);
    if (given != 1) {
      throw new DomainException(
          "transfer.creditor.invalid",
          "Provide exactly one of creditorAccountId, creditor, creditorKey");
    }
    if (r.creditorAccountId() != null) {
      return new AccountRef(institution, new AccountId(r.creditorAccountId()));
    }
    if (r.creditor() != null) {
      return new AccountRef(new Ispb(r.creditor().ispb()), new AccountId(r.creditor().accountId()));
    }
    return keys.lookup(PixKey.of(r.creditorKey().type(), r.creditorKey().value())).account().ref();
  }

  @GetMapping("/{id}")
  TransferResponse get(@PathVariable UUID id) {
    return TransferResponse.of(transfers.get(new TransferId(id)));
  }
}
