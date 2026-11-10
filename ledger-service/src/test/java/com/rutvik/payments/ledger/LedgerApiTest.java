package com.rutvik.payments.ledger;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LedgerApiTest extends IntegrationTest {

    @Test
    void opensAccountAndRejectsDuplicate() throws Exception {
        String body = """
                {"ownerId": "alice", "asset": "INR"}""";
        mvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balance").value(0))
                .andExpect(jsonPath("$.asset").value("INR"));
        mvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_EXISTS"));
    }

    @Test
    void depositThenTransferMovesMoneyOnce() throws Exception {
        long alice = openAccount("alice", "INR");
        long bob = openAccount("bob", "INR");

        mvc.perform(post("/accounts/{id}/deposits", alice).header("Idempotency-Key", "dep-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 100000}"))
                .andExpect(status().isCreated());

        transfer("t-1", alice, bob, 25_000)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.status").value("POSTED"));
        // same key, same body: original result, no second movement
        transfer("t-1", alice, bob, 25_000)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"));

        mvc.perform(get("/accounts/{id}/balance", alice))
                .andExpect(jsonPath("$.balance").value(75_000));
        mvc.perform(get("/accounts/{id}/balance", bob))
                .andExpect(jsonPath("$.balance").value(25_000));
        assertThat(count("select count(*) from transactions where type = 'TRANSFER'")).isEqualTo(1);
    }

    @Test
    void reusedKeyWithDifferentBodyIsConflict() throws Exception {
        long alice = openAccount("alice", "INR");
        long bob = openAccount("bob", "INR");
        fund(alice, "INR", 10_000);

        transfer("t-2", alice, bob, 1_000).andExpect(status().isCreated());
        transfer("t-2", alice, bob, 2_000)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(balance(bob)).isEqualTo(1_000);
    }

    @Test
    void insufficientFundsIsRejectedAndRemembered() throws Exception {
        long alice = openAccount("alice", "INR");
        long bob = openAccount("bob", "INR");
        fund(alice, "INR", 500);

        transfer("t-3", alice, bob, 501)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reason").value("INSUFFICIENT_FUNDS"));

        // Topping up afterwards doesn't make the old request succeed on retry.
        fund(alice, "INR", 10_000);
        transfer("t-3", alice, bob, 501).andExpect(status().isUnprocessableEntity());
        assertThat(balance(bob)).isZero();
        assertThat(count("select count(*) from ledger_entries where transaction_id in "
                + "(select id from transactions where idempotency_key = 't-3')")).isZero();
    }

    @Test
    void missingKeyAndBadBodiesAreBadRequests() throws Exception {
        long alice = openAccount("alice", "INR");
        mvc.perform(post("/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromAccountId\": %d, \"toAccountId\": 99, \"amount\": 10}".formatted(alice)))
                .andExpect(status().isBadRequest());
        transfer("t-4", alice, alice + 1, -5).andExpect(status().isBadRequest());
        transfer("t-5", alice, alice, 5).andExpect(status().isBadRequest());
    }

    @Test
    void unbalancedPostingIsRefused() throws Exception {
        long alice = openAccount("alice", "INR");
        long bob = openAccount("bob", "INR");
        fund(alice, "INR", 1_000);

        mvc.perform(post("/postings").header("Idempotency-Key", "p-1").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "TRANSFER", "legs": [
                                  {"accountId": %d, "asset": "INR", "direction": "DEBIT", "amount": 100},
                                  {"accountId": %d, "asset": "INR", "direction": "CREDIT", "amount": 99}
                                ]}""".formatted(alice, bob)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_POSTING"));
    }

    @Test
    void deliveryVersusPaymentMovesCashAndSharesTogether() throws Exception {
        long buyerCash = openAccount("buyer", "INR");
        long sellerShares = openAccount("seller", "INFY");
        fund(buyerCash, "INR", 1_000_000);
        fund(sellerShares, "INFY", 50);

        dvp("trade-1", 10).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("POSTED"));

        long buyerShares = accounts.findByOwnerAndAsset("buyer", "INFY").orElseThrow().id();
        long sellerCash = accounts.findByOwnerAndAsset("seller", "INR").orElseThrow().id();
        assertThat(balance(buyerCash)).isEqualTo(1_000_000 - 10 * 15_000);
        assertThat(balance(sellerCash)).isEqualTo(10 * 15_000);
        assertThat(balance(sellerShares)).isEqualTo(40);
        assertThat(balance(buyerShares)).isEqualTo(10);
    }

    @Test
    void deliveryVersusPaymentMovesNothingWhenSellerIsShort() throws Exception {
        long buyerCash = openAccount("buyer", "INR");
        long sellerShares = openAccount("seller", "INFY");
        fund(buyerCash, "INR", 1_000_000);
        fund(sellerShares, "INFY", 5);

        dvp("trade-2", 10)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("INSUFFICIENT_SECURITIES"));

        assertThat(balance(buyerCash)).isEqualTo(1_000_000);
        assertThat(balance(sellerShares)).isEqualTo(5);
        assertThat(count("select count(*) from transactions where idempotency_key = 'trade-2' and status = 'REJECTED'"))
                .isEqualTo(1);
    }

    @Test
    void statementIsPagedNewestFirst() throws Exception {
        long alice = openAccount("alice", "INR");
        long bob = openAccount("bob", "INR");
        fund(alice, "INR", 10_000);
        for (int i = 0; i < 5; i++) {
            transfer("s-" + i, alice, bob, 100 + i).andExpect(status().isCreated());
        }
        mvc.perform(get("/accounts/{id}/entries", alice).param("page", "0").param("size", "4"))
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[0].amount").value(104))
                .andExpect(jsonPath("$[0].direction").value("DEBIT"));
        mvc.perform(get("/accounts/{id}/entries", alice).param("page", "1").param("size", "4"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[1].type").value("DEPOSIT"));
    }

    private ResultActions transfer(String key, long from, long to, long amount) throws Exception {
        return mvc.perform(post("/transfers").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromAccountId\": %d, \"toAccountId\": %d, \"amount\": %d}".formatted(from, to, amount)));
    }

    private ResultActions dvp(String tradeId, long qty) throws Exception {
        long price = 15_000;
        return mvc.perform(post("/postings").header("Idempotency-Key", tradeId).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"type": "TRADE_SETTLEMENT", "reference": "%s", "legs": [
                          {"ownerId": "buyer",  "asset": "INR",  "direction": "DEBIT",  "amount": %d},
                          {"ownerId": "seller", "asset": "INR",  "direction": "CREDIT", "amount": %d},
                          {"ownerId": "seller", "asset": "INFY", "direction": "DEBIT",  "amount": %d},
                          {"ownerId": "buyer",  "asset": "INFY", "direction": "CREDIT", "amount": %d}
                        ]}""".formatted(tradeId, price * qty, price * qty, qty, qty)));
    }
}
