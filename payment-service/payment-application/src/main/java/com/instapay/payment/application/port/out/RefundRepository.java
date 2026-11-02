package com.instapay.payment.application.port.out;

import com.instapay.payment.domain.shared.Money;
import com.instapay.payment.domain.transfer.Refund;
import com.instapay.payment.domain.transfer.TransferId;
import java.util.List;

public interface RefundRepository {

  void insert(Refund refund);

  List<Refund> listByTransfer(TransferId transfer);

  Money totalFor(TransferId transfer);
}
