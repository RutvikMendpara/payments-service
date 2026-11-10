package com.rutvik.payments.settlement;

import java.time.Instant;

public record Settlement(
        String tradeId,
        String symbol,
        long price,
        long quantity,
        String buyerId,
        String sellerId,
        SettlementStatus status,
        String reason,
        int attempts,
        Long ledgerTransactionId,
        Instant executedAt,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * The ledger idempotency key. It only changes when an operator retries a FAILED trade,
     * so a crash-and-redeliver of the same attempt can never post twice.
     */
    public String ledgerKey() {
        return "trade-" + tradeId + "-" + attempts;
    }

    public long notional() {
        return Math.multiplyExact(price, quantity);
    }
}
