package com.rutvik.payments.ledger.account;

public enum AccountKind {
    CUSTOMER,
    /** Represents the world outside the ledger (deposits, issuance, FX desk). May go negative. */
    SYSTEM
}
