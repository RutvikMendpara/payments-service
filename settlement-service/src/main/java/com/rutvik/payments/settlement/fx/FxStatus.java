package com.rutvik.payments.settlement.fx;

/**
 * STARTED -> DEBITED -> COMPLETED
 *    |          |
 *    |          +-> COMPENSATING -> COMPENSATED   (credit leg rejected, debit refunded)
 *    +-> FAILED                                    (debit leg rejected, nothing to undo)
 */
public enum FxStatus {
    STARTED,
    DEBITED,
    COMPLETED,
    COMPENSATING,
    COMPENSATED,
    FAILED;

    public boolean terminal() {
        return this == COMPLETED || this == COMPENSATED || this == FAILED;
    }
}
