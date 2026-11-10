package com.rutvik.payments.ledger.posting;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * One side of a posting. The account is given either by id, or by owner and asset
 * (settlement only knows who traded, not our internal account ids).
 */
public record Leg(
        Long accountId,
        @Size(max = 64) String ownerId,
        @NotNull @Size(min = 1, max = 16) String asset,
        @NotNull Direction direction,
        @Positive long amount) {

    public static Leg debit(long accountId, String asset, long amount) {
        return new Leg(accountId, null, asset, Direction.DEBIT, amount);
    }

    public static Leg credit(long accountId, String asset, long amount) {
        return new Leg(accountId, null, asset, Direction.CREDIT, amount);
    }

    String canonical() {
        return (accountId != null ? "id:" + accountId : "owner:" + ownerId)
                + "|" + asset + "|" + direction + "|" + amount;
    }
}
