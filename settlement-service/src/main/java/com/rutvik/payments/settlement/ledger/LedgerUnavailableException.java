package com.rutvik.payments.settlement.ledger;

/** 5xx or similar from the ledger: worth retrying. */
public class LedgerUnavailableException extends RuntimeException {

    public LedgerUnavailableException(String message) {
        super(message);
    }
}
