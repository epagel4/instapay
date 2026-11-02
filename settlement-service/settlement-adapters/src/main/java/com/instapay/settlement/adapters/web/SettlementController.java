package com.instapay.settlement.adapters.web;

import com.instapay.settlement.application.SettleService;
import com.instapay.settlement.domain.Settlement;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SettlementController {

  private final SettleService settle;

  SettlementController(SettleService settle) {
    this.settle = settle;
  }

  @GetMapping("/api/v1/settlements/{transferId}")
  ResponseEntity<Settlement> get(@PathVariable UUID transferId) {
    return ResponseEntity.of(settle.find(transferId));
  }
}
