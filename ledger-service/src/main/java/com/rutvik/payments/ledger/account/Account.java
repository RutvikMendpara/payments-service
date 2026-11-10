package com.rutvik.payments.ledger.account;

import java.time.Instant;

public record Account(long id, String ownerId, String asset, AccountKind kind, String status, Instant createdAt) {

    public boolean active() {
        return "ACTIVE".equals(status);
    }
}
