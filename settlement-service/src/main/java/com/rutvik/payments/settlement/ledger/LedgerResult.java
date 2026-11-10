package com.rutvik.payments.settlement.ledger;

/** What the ledger said about a posting: POSTED with a transaction id, or REJECTED with a reason. */
public record LedgerResult(long transactionId, String status, String reason, boolean replayed) {

    public boolean posted() {
        return "POSTED".equals(status);
    }
}
