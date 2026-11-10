package com.rutvik.payments.ledger.posting;

import java.time.Instant;

public record PostingResult(
        long transactionId,
        String idempotencyKey,
        TransactionType type,
        PostingStatus status,
        String reason,
        String reference,
        Instant createdAt,
        boolean replayed) {

    public boolean posted() {
        return status == PostingStatus.POSTED;
    }

    PostingResult asReplay() {
        return new PostingResult(transactionId, idempotencyKey, type, status, reason, reference, createdAt, true);
    }
}
