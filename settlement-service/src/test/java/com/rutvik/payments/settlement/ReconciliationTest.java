package com.rutvik.payments.settlement;

import com.rutvik.payments.common.events.TradeExecuted;
import com.rutvik.payments.settlement.recon.Mismatch;
import com.rutvik.payments.settlement.recon.ReconReport;
import com.rutvik.payments.settlement.recon.ReconciliationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationTest extends IntegrationTest {

    @Autowired
    ReconciliationService recon;

    @Test
    void findsSettlementsTheLedgerDisagreesWith() throws Exception {
        jdbc.sql("delete from settlements").update();

        ledgerPosts(41);
        TradeExecuted good = trade("b1", "s1", 100, 1);
        publish(good);
        awaitStatus(good.tradeId(), SettlementStatus.SETTLED);

        ledgerAnswers(422, "{\"transactionId\": 0, \"status\": \"REJECTED\", \"reason\": \"INSUFFICIENT_FUNDS\"}");
        TradeExecuted failed = trade("b2", "s2", 100, 1);
        publish(failed);
        awaitStatus(failed.tradeId(), SettlementStatus.FAILED);

        // What the ledger says when asked afterwards: the good one matches, the "failed" one was
        // actually posted (the break we care most about).
        ledger.stubFor(get(urlPathEqualTo("/transactions"))
                .withQueryParam("idempotencyKey", equalTo("trade-" + good.tradeId() + "-1"))
                .willReturn(okJson("{\"transactionId\": 41, \"status\": \"POSTED\"}")));
        ledger.stubFor(get(urlPathEqualTo("/transactions"))
                .withQueryParam("idempotencyKey", equalTo("trade-" + failed.tradeId() + "-1"))
                .willReturn(okJson("{\"transactionId\": 99, \"status\": \"POSTED\"}")));

        ReconReport report = recon.run();

        assertThat(report.checked()).isEqualTo(2);
        assertThat(report.mismatches()).containsExactly(
                new Mismatch(failed.tradeId(), "FAILED_BUT_POSTED", "ledger transaction 99"));
    }

    @Test
    void settledTradeMissingFromLedgerIsABreak() throws Exception {
        jdbc.sql("delete from settlements").update();
        ledgerPosts(55);
        TradeExecuted t = trade("b3", "s3", 100, 1);
        publish(t);
        awaitStatus(t.tradeId(), SettlementStatus.SETTLED);
        ledger.stubFor(get(urlPathEqualTo("/transactions")).willReturn(notFound()));

        ReconReport report = recon.run();

        assertThat(report.mismatches()).extracting(Mismatch::checkName).containsExactly("SETTLED_NOT_IN_LEDGER");
    }
}
