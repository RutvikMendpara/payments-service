package com.rutvik.payments.ledger.posting;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.stream.Collectors;

public record PostingRequest(
        @NotNull TransactionType type,
        @Size(max = 128) String reference,
        @NotNull @Size(min = 2, max = 16) List<@Valid Leg> legs) {

    /** Stable text form used to detect a reused idempotency key with a different body. */
    String canonical() {
        return type + "|" + (reference == null ? "" : reference) + "|"
                + legs.stream().map(Leg::canonical).collect(Collectors.joining(";"));
    }
}
