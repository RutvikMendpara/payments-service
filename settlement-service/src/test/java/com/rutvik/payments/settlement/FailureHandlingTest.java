package com.rutvik.payments.settlement;

import com.rutvik.payments.common.events.TradeExecuted;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import com.rutvik.payments.settlement.ledger.LedgerClient;
import com.rutvik.payments.settlement.ledger.LedgerUnavailableException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FailureHandlingTest extends IntegrationTest {

    @Autowired
    LedgerClient ledgerClient;

    @Test
    void unreadableMessageGoesStraightToDeadLetterTopic() throws Exception {
        kafkaTemplate.send("trades.executed", "garbage-1", "{not json").get();

        ConsumerRecord<String, String> dead = consumeOne("trades.executed.DLT", "garbage-1");
        assertThat(dead.value()).isEqualTo("{not json");
        assertThat(dead.headers().lastHeader("kafka_dlt-exception-fqcn")).isNotNull();
        ledger.verify(0, postRequestedFor(urlEqualTo("/postings")));
    }

    @Test
    void invalidTradeGoesToDeadLetterTopic() throws Exception {
        TradeExecuted bad = new TradeExecuted("T-zero-qty", "INFY", 100, 0, "a", "b", Instant.now());
        kafkaTemplate.send("trades.executed", bad.tradeId(), mapper.writeValueAsString(bad)).get();

        consumeOne("trades.executed.DLT", bad.tradeId());
        assertThat(settlements.find(bad.tradeId())).isEmpty();
    }

    @Test
    void longLedgerOutageDeadLettersThenSweeperFinishesTheJob() throws Exception {
        ledger.stubFor(post(urlEqualTo("/postings")).willReturn(aResponse().withStatus(503)));
        TradeExecuted t = trade("buyer-7", "seller-7", 100, 1);

        publish(t);

        consumeOne("trades.executed.DLT", t.tradeId());
        assertThat(settlements.find(t.tradeId()).orElseThrow().status()).isEqualTo(SettlementStatus.PENDING);

        // ledger comes back; the sweeper re-drives what was left PENDING
        breakers.circuitBreaker("ledger").reset();
        ledgerPosts(1_000);
        service.sweepStalePending();

        assertThat(settlements.find(t.tradeId()).orElseThrow().status()).isEqualTo(SettlementStatus.SETTLED);
    }

    @Test
    void circuitOpensAfterRepeatedFailuresAndStopsCallingTheLedger() {
        ledger.stubFor(post(urlEqualTo("/postings")).willReturn(aResponse().withStatus(500)));
        CircuitBreaker breaker = breakers.circuitBreaker("ledger");

        for (int i = 0; i < 10 && breaker.getState() != CircuitBreaker.State.OPEN; i++) {
            assertThatThrownBy(() -> ledgerClient.post("k", "TRADE_SETTLEMENT", "r", List.of()))
                    .isInstanceOfAny(LedgerUnavailableException.class, CallNotPermittedException.class);
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        int callsSoFar = ledger.findAll(postRequestedFor(urlEqualTo("/postings"))).size();
        assertThatThrownBy(() -> ledgerClient.post("k", "TRADE_SETTLEMENT", "r", List.of()))
                .isInstanceOf(CallNotPermittedException.class);
        assertThat(ledger.findAll(postRequestedFor(urlEqualTo("/postings")))).hasSize(callsSoFar);
    }
}
