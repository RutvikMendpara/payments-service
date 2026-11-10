package com.rutvik.payments.settlement;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("settlement")
public record SettlementProperties(
        String cashAsset,
        String tradesTopic,
        int tradesPartitions,
        Retry retry,
        Sweeper sweeper) {

    /** Blocking retries on the consumer before a trade goes to the dead-letter topic. */
    public record Retry(Duration initialInterval, double multiplier, Duration maxInterval, int maxRetries) {
    }

    /** Picks up trades left PENDING by a crash or a ledger outage. */
    public record Sweeper(Duration interval, Duration pendingAge) {
    }
}
