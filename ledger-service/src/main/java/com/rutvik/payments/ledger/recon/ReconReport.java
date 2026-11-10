package com.rutvik.payments.ledger.recon;

import java.time.Instant;
import java.util.List;

public record ReconReport(long runId, String status, Instant startedAt, Instant finishedAt, int mismatchCount,
                          List<Mismatch> mismatches) {

    public boolean clean() {
        return "COMPLETED".equals(status) && mismatchCount == 0;
    }
}
