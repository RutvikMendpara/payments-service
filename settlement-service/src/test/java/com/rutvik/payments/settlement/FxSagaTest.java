package com.rutvik.payments.settlement;

import com.rutvik.payments.settlement.fx.FxStatus;
import com.rutvik.payments.settlement.fx.FxTransfer;
import com.rutvik.payments.settlement.fx.FxTransferSaga;
import com.rutvik.payments.settlement.fx.FxTransferSaga.FxRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FxSagaTest extends IntegrationTest {

    @Autowired
    FxTransferSaga saga;

    private static void step(String suffix, int status, String body) {
        ledger.stubFor(post(urlEqualTo("/postings")).withHeader("Idempotency-Key", matching(".*-" + suffix))
                .willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)));
    }

    private static final String POSTED = "{\"transactionId\": 1, \"status\": \"POSTED\"}";

    @Test
    void convertsInrToUsdInTwoLegs() {
        step("debit", 201, POSTED);
        step("credit", 201, POSTED);

        FxTransfer t = saga.start("fx-" + UUID.randomUUID(), new FxRequest("alice", "bob", "INR", "USD", 1_000_000));

        assertThat(t.status()).isEqualTo(FxStatus.COMPLETED);
        assertThat(t.targetAmount()).isEqualTo(12_012); // ₹10,000.00 at 0.012012 = $120.12
        ledger.verify(postRequestedFor(urlEqualTo("/postings")).withRequestBody(equalToJson("""
                {"type": "FX_CONVERSION", "legs": [
                  {"ownerId": "alice",   "asset": "INR", "direction": "DEBIT",  "amount": 1000000},
                  {"ownerId": "FX:DESK", "asset": "INR", "direction": "CREDIT", "amount": 1000000}]}""", true, true)));
        ledger.verify(postRequestedFor(urlEqualTo("/postings")).withRequestBody(equalToJson("""
                {"type": "FX_CONVERSION", "legs": [
                  {"ownerId": "FX:DESK", "asset": "USD", "direction": "DEBIT",  "amount": 12012},
                  {"ownerId": "bob",     "asset": "USD", "direction": "CREDIT", "amount": 12012}]}""", true, true)));
    }

    @Test
    void refundsTheDebitWhenTheCreditLegIsRejected() {
        step("debit", 201, POSTED);
        step("credit", 422, "{\"transactionId\": 2, \"status\": \"REJECTED\", \"reason\": \"INSUFFICIENT_FUNDS\"}");
        step("refund", 201, POSTED);

        FxTransfer t = saga.start("fx-" + UUID.randomUUID(), new FxRequest("alice", "bob", "INR", "USD", 500_000));

        assertThat(t.status()).isEqualTo(FxStatus.COMPENSATED);
        assertThat(t.reason()).isEqualTo("INSUFFICIENT_FUNDS");
        ledger.verify(exactly(1), postRequestedFor(urlEqualTo("/postings")).withRequestBody(equalToJson("""
                {"type": "REVERSAL", "legs": [
                  {"ownerId": "FX:DESK", "asset": "INR", "direction": "DEBIT",  "amount": 500000},
                  {"ownerId": "alice",   "asset": "INR", "direction": "CREDIT", "amount": 500000}]}""", true, true)));
    }

    @Test
    void customerWithoutFundsFailsWithNothingToUndo() {
        step("debit", 422, "{\"transactionId\": 3, \"status\": \"REJECTED\", \"reason\": \"INSUFFICIENT_FUNDS\"}");

        FxTransfer t = saga.start("fx-" + UUID.randomUUID(), new FxRequest("alice", "bob", "INR", "USD", 500_000));

        assertThat(t.status()).isEqualTo(FxStatus.FAILED);
        ledger.verify(exactly(1), postRequestedFor(urlEqualTo("/postings")));
    }

    @Test
    void sameKeyReturnsTheSameSagaWithoutNewPostings() {
        step("debit", 201, POSTED);
        step("credit", 201, POSTED);
        String key = "fx-" + UUID.randomUUID();
        FxRequest req = new FxRequest("alice", "bob", "INR", "USD", 100_000);

        FxTransfer first = saga.start(key, req);
        FxTransfer again = saga.start(key, req);

        assertThat(again.id()).isEqualTo(first.id());
        ledger.verify(exactly(2), postRequestedFor(urlEqualTo("/postings")));
    }

    @Test
    void sagaInterruptedAfterTheDebitResumesWithTheSameKeys() {
        // ledger is down for the credit leg: the saga stops at DEBITED
        step("debit", 201, POSTED);
        ledger.stubFor(post(urlEqualTo("/postings")).withHeader("Idempotency-Key", matching(".*-credit"))
                .willReturn(aResponse().withStatus(503)));
        String key = "fx-" + UUID.randomUUID();
        try {
            saga.start(key, new FxRequest("alice", "bob", "INR", "USD", 200_000));
        } catch (RuntimeException expected) {
            // retries exhausted
        }
        FxTransfer stuck = jdbc.sql("select id from fx_transfers where idempotency_key = :k").param("k", key)
                .query(UUID.class).optional().flatMap(id -> findSaga(id)).orElseThrow();
        assertThat(stuck.status()).isEqualTo(FxStatus.DEBITED);

        // ledger back; recovery picks it up (backdate so it counts as stale)
        breakers.circuitBreaker("ledger").reset();
        step("credit", 201, POSTED);
        jdbc.sql("update fx_transfers set updated_at = now() - interval '5 minutes' where id = :id")
                .param("id", stuck.id()).update();
        saga.recover();

        assertThat(findSaga(stuck.id()).orElseThrow().status()).isEqualTo(FxStatus.COMPLETED);
        ledger.verify(exactly(1), postRequestedFor(urlEqualTo("/postings"))
                .withHeader("Idempotency-Key", matching("fx-" + stuck.id() + "-debit")));
    }

    @Test
    void apiNeedsFxScope() throws Exception {
        String body = "{\"fromOwner\":\"a\",\"toOwner\":\"b\",\"fromCurrency\":\"INR\",\"toCurrency\":\"USD\",\"amount\":100000}";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/fx-transfers")
                        .header("Idempotency-Key", "k1").contentType("application/json").content(body)
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_settlement.read"))))
                .andExpect(status().isForbidden());

        step("debit", 201, POSTED);
        step("credit", 201, POSTED);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/fx-transfers")
                        .header("Idempotency-Key", "k2-" + UUID.randomUUID()).contentType("application/json").content(body)
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_fx.write"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.targetAmount").value(1201));
    }

    private java.util.Optional<FxTransfer> findSaga(UUID id) {
        return jdbc.sql("select status from fx_transfers where id = :id").param("id", id).query(String.class).optional()
                .map(s -> new FxTransfer(id, null, null, null, null, null, 0, 0, 0, FxStatus.valueOf(s), null, null, null));
    }
}
