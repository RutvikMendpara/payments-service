package com.rutvik.payments.settlement.fx;

import com.rutvik.payments.common.outbox.OutboxWriter;
import com.rutvik.payments.settlement.ledger.LedgerClient;
import com.rutvik.payments.settlement.ledger.LedgerClient.LedgerLeg;
import com.rutvik.payments.settlement.ledger.LedgerResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigInteger;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cross-currency transfer as an orchestrated saga against the ledger:
 *
 *   1. debit   customer's source currency  -> FX desk (source currency)
 *   2. credit  FX desk (target currency)   -> recipient's target currency
 *   3. refund  (only if 2 is rejected)     FX desk -> customer, undoing step 1
 *
 * Each step is its own ledger posting, the way it would be if each currency lived in its own
 * ledger or nostro account, so no single DB transaction covers the whole thing. Saga state is
 * persisted before and after every step and each step has a fixed idempotency key, so a crash
 * at any point is fixed by running the saga again from its stored state.
 */
@Service
public class FxTransferSaga {

    static final String DESK = "FX:DESK";
    private static final Logger log = LoggerFactory.getLogger(FxTransferSaga.class);

    private final FxRepository repo;
    private final LedgerClient ledger;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;

    public FxTransferSaga(FxRepository repo, LedgerClient ledger, OutboxWriter outbox, TransactionTemplate tx) {
        this.repo = repo;
        this.ledger = ledger;
        this.outbox = outbox;
        this.tx = tx;
    }

    public record FxRequest(String fromOwner, String toOwner, String fromCurrency, String toCurrency, long amount) {
    }

    public FxTransfer start(String idempotencyKey, FxRequest req) {
        if (req.amount() <= 0 || req.fromCurrency().equals(req.toCurrency())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "need a positive amount and two different currencies");
        }
        long rate = repo.rate(req.fromCurrency(), req.toCurrency())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "no rate for " + req.fromCurrency() + "/" + req.toCurrency()));
        // Round down: the customer never receives more than the rate allows.
        long target = BigInteger.valueOf(req.amount()).multiply(BigInteger.valueOf(rate))
                .divide(BigInteger.valueOf(1_000_000)).longValueExact();
        if (target == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount too small to convert");
        }
        FxTransfer saga = repo.startOrGet(new FxTransfer(UUID.randomUUID(), idempotencyKey, req.fromOwner(),
                req.toOwner(), req.fromCurrency(), req.toCurrency(), req.amount(), target, rate,
                FxStatus.STARTED, null, null, null));
        if (saga.sourceAmount() != req.amount() || !saga.fromOwner().equals(req.fromOwner())
                || !saga.toOwner().equals(req.toOwner()) || !saga.toCurrency().equals(req.toCurrency())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "idempotency key reused with a different request");
        }
        return run(saga);
    }

    /** Drives a saga from whatever state it is in to a terminal state (or until the ledger is unreachable). */
    FxTransfer run(FxTransfer saga) {
        FxTransfer s = saga;
        while (!s.status().terminal()) {
            switch (s.status()) {
                case STARTED -> {
                    LedgerResult r = ledger.post(s.debitKey(), "FX_CONVERSION", "fx " + s.id() + " debit", List.of(
                            new LedgerLeg(s.fromOwner(), s.fromCurrency(), "DEBIT", s.sourceAmount()),
                            new LedgerLeg(DESK, s.fromCurrency(), "CREDIT", s.sourceAmount())));
                    if (r.posted()) {
                        repo.advance(s.id(), FxStatus.STARTED, FxStatus.DEBITED, null);
                    } else {
                        finish(s, FxStatus.STARTED, FxStatus.FAILED, r.reason());
                    }
                }
                case DEBITED -> {
                    LedgerResult r = ledger.post(s.creditKey(), "FX_CONVERSION", "fx " + s.id() + " credit", List.of(
                            new LedgerLeg(DESK, s.toCurrency(), "DEBIT", s.targetAmount()),
                            new LedgerLeg(s.toOwner(), s.toCurrency(), "CREDIT", s.targetAmount())));
                    if (r.posted()) {
                        finish(s, FxStatus.DEBITED, FxStatus.COMPLETED, null);
                    } else {
                        log.warn("fx {} credit leg rejected ({}), compensating", s.id(), r.reason());
                        repo.advance(s.id(), FxStatus.DEBITED, FxStatus.COMPENSATING, r.reason());
                    }
                }
                case COMPENSATING -> {
                    LedgerResult r = ledger.post(s.refundKey(), "REVERSAL", "fx " + s.id() + " refund", List.of(
                            new LedgerLeg(DESK, s.fromCurrency(), "DEBIT", s.sourceAmount()),
                            new LedgerLeg(s.fromOwner(), s.fromCurrency(), "CREDIT", s.sourceAmount())));
                    if (!r.posted()) {
                        // The desk just received this money in step 1, so this should never happen.
                        // Leave it COMPENSATING for the recovery job and for someone to look at.
                        throw new IllegalStateException("refund for fx " + s.id() + " rejected: " + r.reason());
                    }
                    finish(s, FxStatus.COMPENSATING, FxStatus.COMPENSATED, null);
                }
                default -> throw new IllegalStateException(s.status().name());
            }
            s = repo.find(s.id()).orElseThrow();
        }
        return s;
    }

    /** Resumes sagas a crash or a ledger outage left half way. */
    @Scheduled(fixedDelayString = "${fx.recovery-interval:30s}")
    public void recover() {
        for (FxTransfer s : repo.unfinished(Duration.ofSeconds(30))) {
            try {
                run(s);
            } catch (RuntimeException e) {
                log.warn("fx {} still stuck in {}: {}", s.id(), s.status(), e.getMessage());
                return;
            }
        }
    }

    private void finish(FxTransfer s, FxStatus from, FxStatus to, String reason) {
        tx.executeWithoutResult(status -> {
            if (repo.advance(s.id(), from, to, reason)) {
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("fxTransferId", s.id().toString());
                event.put("status", to.name());
                event.put("from", s.fromOwner() + " " + s.sourceAmount() + " " + s.fromCurrency());
                event.put("to", s.toOwner() + " " + s.targetAmount() + " " + s.toCurrency());
                event.put("rateMicros", s.rateMicros());
                if (reason != null || s.reason() != null) {
                    event.put("reason", reason != null ? reason : s.reason());
                }
                outbox.write("fx.transfers", s.id().toString(), "FxTransfer" + to, event);
            }
        });
    }
}
