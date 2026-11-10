package com.rutvik.payments.ledger.account;

import java.time.Instant;

public record AccountView(long id, String ownerId, String asset, AccountKind kind, String status,
                          long balance, long version, Instant createdAt) {
}
