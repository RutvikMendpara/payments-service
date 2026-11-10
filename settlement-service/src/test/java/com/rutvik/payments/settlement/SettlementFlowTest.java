package com.rutvik.payments.settlement;

import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.rutvik.payments.common.events.TradeExecuted;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class SettlementFlowTest extends IntegrationTest {

    @Test
    void tradeIsSettledThroughTheLedgerAndAnnounced() throws Exception {
        ledgerPosts(501);
        TradeExecuted t = trade("buyer-1", "seller-1", 15_000, 10);

        publish(t);

        Settlement s = awaitStatus(t.tradeId(), SettlementStatus.SETTLED);
        assertThat(s.ledgerTransactionId()).isEqualTo(501);
        ledger.verify(exactly(1), postRequestedFor(urlEqualTo("/postings"))
                .withHeader("Idempotency-Key", equalTo("trade-" + t.tradeId() + "-1"))
                .withHeader("Authorization", equalTo("Bearer test-token"))
                .withRequestBody(equalToJson("""
                        {"type": "TRADE_SETTLEMENT", "legs": [
                          {"ownerId": "buyer-1",  "asset": "INR",  "direction": "DEBIT",  "amount": 150000},
                          {"ownerId": "seller-1", "asset": "INR",  "direction": "CREDIT", "amount": 150000},
                          {"ownerId": "seller-1", "asset": "INFY", "direction": "DEBIT",  "amount": 10},
                          {"ownerId": "buyer-1",  "asset": "INFY", "direction": "CREDIT", "amount": 10}
                        ]}""", true, true)));

        ConsumerRecord<String, String> event = consumeOne("settlement.completed", t.tradeId());
        assertThat(event.value()).contains("\"ledgerTransactionId\": 501");
    }

    @Test
    void sameTradeDeliveredTwiceSettlesOnce() throws Exception {
        ledgerPosts(502);
        TradeExecuted t = trade("buyer-2", "seller-2", 100, 1);

        publish(t);
        publish(t);
        awaitStatus(t.tradeId(), SettlementStatus.SETTLED);
        publish(t); // and once more, well after it settled

        TradeExecuted marker = trade("buyer-2", "seller-2", 100, 2);
        publish(marker); // same partition? not guaranteed, so wait on the counter instead
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(jdbc.sql("select count(*) from processed_events where event_id = :id")
                        .param("id", t.tradeId()).query(Long.class).single()).isEqualTo(1));
        Thread.sleep(1_000);

        ledger.verify(exactly(1), postRequestedFor(urlEqualTo("/postings"))
                .withRequestBody(matchingJsonPath("$.reference", equalTo("trade " + t.tradeId()))));
        assertThat(jdbc.sql("select count(*) from outbox where event_key = :id").param("id", t.tradeId())
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void redeliveryAfterCrashReusesTheSameLedgerKey() throws Exception {
        // State left by a crash after the ledger committed but before we recorded SETTLED.
        TradeExecuted t = trade("buyer-3", "seller-3", 2_000, 3);
        settlements.insertPendingIfAbsent(t);
        ledgerAnswers(201, """
                {"transactionId": 777, "status": "POSTED", "reason": null, "replayed": true}""");

        publish(t);

        Settlement s = awaitStatus(t.tradeId(), SettlementStatus.SETTLED);
        assertThat(s.ledgerTransactionId()).isEqualTo(777);
        ledger.verify(exactly(1), postRequestedFor(urlEqualTo("/postings"))
                .withHeader("Idempotency-Key", equalTo("trade-" + t.tradeId() + "-1")));
    }

    @Test
    void insufficientFundsMarksFailedAndPublishesFailure() throws Exception {
        ledgerAnswers(422, """
                {"transactionId": 9, "status": "REJECTED", "reason": "INSUFFICIENT_FUNDS", "replayed": false}""");
        TradeExecuted t = trade("poor-buyer", "seller-4", 50_000, 100);

        publish(t);

        Settlement s = awaitStatus(t.tradeId(), SettlementStatus.FAILED);
        assertThat(s.reason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(s.ledgerTransactionId()).isNull();
        assertThat(consumeOne("settlement.failed", t.tradeId()).value()).contains("INSUFFICIENT_FUNDS");
    }

    @Test
    void shortLedgerBlipIsRetriedInPlace() throws Exception {
        ledger.stubFor(post(urlEqualTo("/postings")).inScenario("blip").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("second"));
        ledger.stubFor(post(urlEqualTo("/postings")).inScenario("blip").whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(503)).willSetStateTo("up"));
        ledger.stubFor(post(urlEqualTo("/postings")).inScenario("blip").whenScenarioStateIs("up")
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"transactionId\": 600, \"status\": \"POSTED\"}")));
        TradeExecuted t = trade("buyer-5", "seller-5", 10, 10);

        publish(t);

        awaitStatus(t.tradeId(), SettlementStatus.SETTLED);
        ledger.verify(exactly(3), postRequestedFor(urlEqualTo("/postings")));
    }

    @Test
    void operatorRetryUsesAFreshKeyAfterFailure() throws Exception {
        ledgerAnswers(422, """
                {"transactionId": 10, "status": "REJECTED", "reason": "INSUFFICIENT_SECURITIES"}""");
        TradeExecuted t = trade("buyer-6", "short-seller", 1_000, 5);
        publish(t);
        awaitStatus(t.tradeId(), SettlementStatus.FAILED);

        ledgerPosts(900); // seller got their shares in the meantime
        Settlement retried = service.retry(t.tradeId()).orElseThrow();

        assertThat(retried.status()).isEqualTo(SettlementStatus.SETTLED);
        assertThat(retried.attempts()).isEqualTo(2);
        ledger.verify(exactly(1), postRequestedFor(urlEqualTo("/postings"))
                .withHeader("Idempotency-Key", equalTo("trade-" + t.tradeId() + "-2")));
    }
}
