package com.rutvik.payments.ledger.posting;

public enum Direction {
    DEBIT,
    CREDIT;

    /** Balances are credit-normal: a credit adds to the holder's balance, a debit takes from it. */
    public long signed(long amount) {
        return this == CREDIT ? amount : -amount;
    }
}
