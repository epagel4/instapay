package com.instapay.payment.adapters.web;

import com.instapay.payment.application.transfer.RefundService;
import com.instapay.payment.application.transfer.RefundService.RefundSummary;
import com.instapay.payment.domain.shared.Money;
import com.instapay.payment.domain.transfer.Refund;
import com.instapay.payment.domain.transfer.TransferId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transfers/{transferId}/refunds")
class RefundController {

  record RefundRequest(@NotBlank String amount, String reason) {}

  record RefundResponse(
      UUID id, UUID originalTransferId, String amount, String reason, Instant createdAt) {

    static RefundResponse of(Refund r) {
      return new RefundResponse(
          r.id().value(),
          r.originalTransfer().value(),
          r.amount().toString(),
          r.reason(),
          r.createdAt());
    }
  }

  record RefundsResponse(
      String originalAmount,
      String totalRefunded,
      String refundable,
      List<RefundResponse> refunds) {

    static RefundsResponse of(RefundSummary s) {
      return new RefundsResponse(
          s.transfer().amount().toString(),
          s.totalRefunded().toString(),
          s.refundable().toString(),
          s.refunds().stream().map(RefundResponse::of).toList());
    }
  }

  private final RefundService refunds;

  RefundController(RefundService refunds) {
    this.refunds = refunds;
  }

  /** Total or partial refund, paid by the original creditor to the original debtor. */
  @PostMapping
  ResponseEntity<RefundResponse> refund(
      @PathVariable UUID transferId, @Valid @RequestBody RefundRequest request) {
    Refund refund =
        refunds.refund(new TransferId(transferId), Money.of(request.amount()), request.reason());
    return ResponseEntity.created(URI.create("/api/v1/transfers/" + transferId + "/refunds"))
        .body(RefundResponse.of(refund));
  }

  @GetMapping
  RefundsResponse list(@PathVariable UUID transferId) {
    return RefundsResponse.of(refunds.summary(new TransferId(transferId)));
  }
}
