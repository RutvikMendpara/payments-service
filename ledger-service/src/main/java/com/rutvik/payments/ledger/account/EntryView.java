package com.rutvik.payments.ledger.account;

import com.rutvik.payments.ledger.posting.Direction;
import com.rutvik.payments.ledger.posting.TransactionType;

import java.time.Instant;

public record EntryView(long entryId, long transactionId, TransactionType type, String reference,
                        Direction direction, long amount, Instant createdAt) {
}
