package com.rutvik.payments.settlement.ledger;

/** 4xx other than "insufficient funds": our request is wrong, retrying won't help. */
public class LedgerRequestRejectedException extends RuntimeException {

    private final int status;

    public LedgerRequestRejectedException(int status, String body) {
        super("ledger refused posting with " + status + ": " + body);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
