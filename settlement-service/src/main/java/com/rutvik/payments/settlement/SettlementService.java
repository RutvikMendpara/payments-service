package com.rutvik.payments.settlement;

import com.rutvik.payments.common.events.TradeExecuted;
import com.rutvik.payments.common.outbox.OutboxWriter;
import com.rutvik.payments.settlement.kafka.InvalidTradeException;
import com.rutvik.payments.settlement.ledger.LedgerClient;
import com.rutvik.payments.settlement.ledger.LedgerClient.LedgerLeg;
import com.rutvik.payments.settlement.ledger.LedgerRequestRejectedException;
import com.rutvik.payments.settlement.ledger.LedgerResult;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Settles one trade: record it as PENDING, ask the ledger to post the four DvP legs in one
 * transaction, then record the outcome and its event together.
 *
 * The ledger call can't share a DB transaction with our own tables, so correctness rests on
 * the ledger's idempotency key: if we die after the ledger committed but before we wrote
 * SETTLED, the redelivered trade (or the sweeper) sends the same key and gets the original
 * result back instead of moving money twice.
 */
@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private final SettlementRepository settlements;
    private final LedgerClient ledger;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final SettlementProperties props;
    private final MeterRegistry meters;

    public SettlementService(SettlementRepository settlements, LedgerClient ledger, OutboxWriter outbox,
                             TransactionTemplate tx, SettlementProperties props, MeterRegistry meters) {
        this.settlements = settlements;
        this.ledger = ledger;
        this.outbox = outbox;
        this.tx = tx;
        this.props = props;
        this.meters = meters;
    }

    public void onTrade(TradeExecuted trade) {
        validate(trade);
        if (settlements.isProcessed(trade.tradeId())) {
            meters.counter("settlement.duplicates").increment();
            log.debug("trade {} already processed, skipping", trade.tradeId());
            return;
        }
        Settlement s = settlements.insertPendingIfAbsent(trade);
        if (s.status() != SettlementStatus.PENDING) {
            meters.counter("settlement.duplicates").increment();
            return;
        }
        settle(s);
    }

    /** Operator action for a FAILED trade, e.g. after the buyer has been funded. */
    public Optional<Settlement> retry(String tradeId) {
        Optional<Settlement> reopened = settlements.reopenForRetry(tradeId);
        reopened.ifPresent(this::settle);
        return settlements.find(tradeId);
    }

    /** Re-drives PENDING trades left behind by a crash or by a ledger outage that sent them to the DLT. */
    public int sweepStalePending() {
        List<Settlement> stale = settlements.stalePending(props.sweeper().pendingAge(), 100);
        int done = 0;
        for (Settlement s : stale) {
            try {
                settle(s);
                done++;
            } catch (RuntimeException e) {
                log.warn("sweeper could not settle {} yet: {}", s.tradeId(), e.getMessage());
                break; // ledger probably still down, try again next run
            }
        }
        return done;
    }

    private void settle(Settlement s) {
        LedgerResult result;
        try {
            result = ledger.post(s.ledgerKey(), "TRADE_SETTLEMENT", "trade " + s.tradeId(), dvpLegs(s));
        } catch (LedgerRequestRejectedException e) {
            log.error("ledger refused trade {}: {}", s.tradeId(), e.getMessage());
            result = new LedgerResult(0, "REJECTED", "LEDGER_REFUSED_" + e.status(), false);
        }

        LedgerResult r = result;
        SettlementStatus status = r.posted() ? SettlementStatus.SETTLED : SettlementStatus.FAILED;
        boolean completed = Boolean.TRUE.equals(tx.execute(txStatus -> {
            if (!settlements.complete(s, status, r.reason(), r.posted() ? r.transactionId() : null)) {
                return false;
            }
            settlements.markProcessed(s.tradeId());
            outbox.write(status == SettlementStatus.SETTLED ? "settlement.completed" : "settlement.failed",
                    s.tradeId(),
                    status == SettlementStatus.SETTLED ? "SettlementCompleted" : "SettlementFailed",
                    event(s, status, r));
            return true;
        }));

        if (completed) {
            meters.counter("settlement.processed", "status", status.name()).increment();
            if (s.executedAt() != null) {
                meters.timer("settlement.latency").record(Duration.between(s.executedAt(), Instant.now()));
            }
            log.info("trade {} {}{}", s.tradeId(), status, r.reason() == null ? "" : " (" + r.reason() + ")");
        }
    }

    private List<LedgerLeg> dvpLegs(Settlement s) {
        String cash = props.cashAsset();
        long notional = s.notional();
        return List.of(
                new LedgerLeg(s.buyerId(), cash, "DEBIT", notional),
                new LedgerLeg(s.sellerId(), cash, "CREDIT", notional),
                new LedgerLeg(s.sellerId(), s.symbol(), "DEBIT", s.quantity()),
                new LedgerLeg(s.buyerId(), s.symbol(), "CREDIT", s.quantity()));
    }

    private static Map<String, Object> event(Settlement s, SettlementStatus status, LedgerResult r) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("tradeId", s.tradeId());
        e.put("status", status.name());
        e.put("symbol", s.symbol());
        e.put("price", s.price());
        e.put("quantity", s.quantity());
        e.put("buyerId", s.buyerId());
        e.put("sellerId", s.sellerId());
        if (r.posted()) {
            e.put("ledgerTransactionId", r.transactionId());
        } else {
            e.put("reason", r.reason());
        }
        e.put("at", Instant.now().toString());
        return e;
    }

    private static void validate(TradeExecuted t) {
        if (t.tradeId() == null || t.tradeId().isBlank() || t.tradeId().length() > 64) {
            throw new InvalidTradeException("missing or oversized tradeId");
        }
        if (t.symbol() == null || !t.symbol().matches("^[A-Z0-9]{1,16}$")) {
            throw new InvalidTradeException("bad symbol on trade " + t.tradeId());
        }
        if (t.price() <= 0 || t.quantity() <= 0) {
            throw new InvalidTradeException("non-positive price or quantity on trade " + t.tradeId());
        }
        if (t.buyerId() == null || t.sellerId() == null || t.buyerId().equals(t.sellerId())) {
            throw new InvalidTradeException("bad counterparties on trade " + t.tradeId());
        }
        try {
            t.notional();
        } catch (ArithmeticException e) {
            throw new InvalidTradeException("notional overflows on trade " + t.tradeId());
        }
    }
}
