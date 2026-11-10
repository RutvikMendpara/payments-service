package com.rutvik.payments.ledger;

import com.rutvik.payments.ledger.posting.Leg;
import com.rutvik.payments.ledger.posting.PostingRequest;
import com.rutvik.payments.ledger.posting.TransactionType;
import com.rutvik.payments.ledger.recon.Mismatch;
import com.rutvik.payments.ledger.recon.ReconReport;
import com.rutvik.payments.ledger.recon.ReconciliationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReconciliationTest extends IntegrationTest {

    @Autowired
    ReconciliationService recon;

    @Test
    void healthyLedgerReconcilesClean() throws Exception {
        long alice = openAccount("alice", "INR");
        long bob = openAccount("bob", "INR");
        fund(alice, "INR", 10_000);
        postings.post("t1", new PostingRequest(TransactionType.TRANSFER, null,
                List.of(Leg.debit(alice, "INR", 4_000), Leg.credit(bob, "INR", 4_000))));

        ReconReport report = recon.run();

        assertThat(report.status()).isEqualTo("COMPLETED");
        assertThat(report.clean()).isTrue();
    }

    @Test
    void driftedBalanceIsReported() throws Exception {
        long alice = openAccount("alice", "INR");
        fund(alice, "INR", 10_000);
        // someone "fixes" a balance by hand, bypassing the ledger
        jdbc.sql("update balances set balance = balance + 1 where account_id = :id").param("id", alice).update();

        ReconReport report = recon.run();

        assertThat(report.mismatchCount()).isEqualTo(2); // the drifted account, and INR no longer nets to zero
        assertThat(report.mismatches()).extracting(Mismatch::checkName)
                .containsExactlyInAnyOrder("BALANCE_DRIFT", "ASSET_TOTAL");
        Mismatch drift = report.mismatches().stream().filter(m -> m.checkName().equals("BALANCE_DRIFT")).findFirst()
                .orElseThrow();
        assertThat(drift.reference()).isEqualTo(Long.toString(alice));
        assertThat(drift.expected()).isEqualTo(10_000);
        assertThat(drift.actual()).isEqualTo(10_001);
    }

    @Test
    void unbalancedTransactionIsReportedEvenIfTheDbGuardWasBypassed() throws Exception {
        long alice = openAccount("alice", "INR");
        jdbc.sql("alter table ledger_entries disable trigger ledger_entries_balanced").update();
        try {
            long txId = jdbc.sql("""
                    insert into transactions (idempotency_key, request_hash, type, status)
                    values ('bad', repeat('0', 64), 'TRANSFER', 'POSTED') returning id""").query(Long.class).single();
            jdbc.sql("insert into ledger_entries (transaction_id, account_id, asset, direction, amount) "
                    + "values (:tx, :acc, 'INR', 'CREDIT', 500)").param("tx", txId).param("acc", alice).update();
        } finally {
            jdbc.sql("alter table ledger_entries enable trigger ledger_entries_balanced").update();
        }

        ReconReport report = recon.run();

        assertThat(report.mismatches()).extracting(Mismatch::checkName).contains("UNBALANCED_TRANSACTION");
    }

    @Test
    void reportIsAvailableOverHttp() throws Exception {
        mvc.perform(post("/reconciliation/runs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        mvc.perform(get("/reconciliation/runs/latest")).andExpect(status().isOk())
                .andExpect(jsonPath("$.mismatchCount").value(0));
    }
}
