package com.rutvik.payments.settlement.recon;

import java.util.List;

public record ReconReport(long runId, String status, int checked, int mismatchCount, List<Mismatch> mismatches) {
}
