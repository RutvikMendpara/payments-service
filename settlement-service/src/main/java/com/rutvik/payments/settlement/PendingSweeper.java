package com.rutvik.payments.settlement;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "settlement.sweeper.enabled", havingValue = "true", matchIfMissing = true)
class PendingSweeper {

    private final SettlementService service;

    PendingSweeper(SettlementService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${settlement.sweeper.interval}")
    void sweep() {
        service.sweepStalePending();
    }
}
