package com.rutvik.payments.settlement.fx;

import java.time.Instant;
import java.util.UUID;

public record FxTransfer(
        UUID id,
        String idempotencyKey,
        String fromOwner,
        String toOwner,
        String fromCurrency,
        String toCurrency,
        long sourceAmount,
        long targetAmount,
        long rateMicros,
        FxStatus status,
        String reason,
        Instant createdAt,
        Instant updatedAt) {

    // One ledger key per step, fixed for the life of the saga, so resuming after a crash
    // can never post a step twice.
    String debitKey() {
        return "fx-" + id + "-debit";
    }

    String creditKey() {
        return "fx-" + id + "-credit";
    }

    String refundKey() {
        return "fx-" + id + "-refund";
    }
}
