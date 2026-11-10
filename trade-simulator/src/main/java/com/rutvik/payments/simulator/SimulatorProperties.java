package com.rutvik.payments.simulator;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties("simulator")
public record SimulatorProperties(
        String ledgerUrl,
        String tokenUri,
        String clientId,
        String clientSecret,
        boolean seed,
        int traders,
        List<String> symbols,
        long startingCash,
        long startingShares,
        int trades,
        int ratePerSecond,
        int duplicatePercent) {
}
