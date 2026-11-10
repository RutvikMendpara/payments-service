package com.rutvik.payments.settlement.recon;

import com.rutvik.payments.settlement.Settlement;
import com.rutvik.payments.settlement.SettlementStatus;
import com.rutvik.payments.settlement.ledger.LedgerClient;
import com.rutvik.payments.settlement.ledger.LedgerResult;
import org.springframework.batch.item.ItemProcessor;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Compares what settlement believes with what the ledger actually booked under the same key.
 * The dangerous break is FAILED here but POSTED in the ledger: money moved and nobody knows.
 */
class SettlementVsLedger implements ItemProcessor<Settlement, Mismatch> {

    private final LedgerClient ledger;
    private final Duration stuckAfter;

    SettlementVsLedger(LedgerClient ledger, Duration stuckAfter) {
        this.ledger = ledger;
        this.stuckAfter = stuckAfter;
    }

    @Override
    public Mismatch process(Settlement s) {
        if (s.status() == SettlementStatus.PENDING) {
            return s.updatedAt().isBefore(Instant.now().minus(stuckAfter))
                    ? new Mismatch(s.tradeId(), "STUCK_PENDING", "pending since " + s.updatedAt())
                    : null;
        }
        Optional<LedgerResult> booked = ledger.find(s.ledgerKey());
        if (s.status() == SettlementStatus.SETTLED) {
            if (booked.isEmpty() || !booked.get().posted()) {
                return new Mismatch(s.tradeId(), "SETTLED_NOT_IN_LEDGER", "ledger has " + booked.map(LedgerResult::status).orElse("nothing"));
            }
            if (booked.get().transactionId() != s.ledgerTransactionId()) {
                return new Mismatch(s.tradeId(), "LEDGER_TX_MISMATCH",
                        "we recorded " + s.ledgerTransactionId() + ", ledger has " + booked.get().transactionId());
            }
            return null;
        }
        // FAILED
        if (booked.isPresent() && booked.get().posted()) {
            return new Mismatch(s.tradeId(), "FAILED_BUT_POSTED", "ledger transaction " + booked.get().transactionId());
        }
        return null;
    }
}
