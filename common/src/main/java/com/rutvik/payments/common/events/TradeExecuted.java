package com.rutvik.payments.common.events;

import java.time.Instant;

/**
 * Published by the matching engine on trades.executed.
 * price is paise per share, quantity is whole shares.
 */
public record TradeExecuted(
        String tradeId,
        String symbol,
        long price,
        long quantity,
        String buyerId,
        String sellerId,
        Instant executedAt) {

    public long notional() {
        return Math.multiplyExact(price, quantity);
    }
}
