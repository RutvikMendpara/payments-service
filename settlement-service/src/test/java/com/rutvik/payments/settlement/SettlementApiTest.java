package com.rutvik.payments.settlement;

import com.rutvik.payments.common.events.TradeExecuted;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SettlementApiTest extends IntegrationTest {

    private static final SimpleGrantedAuthority READ = new SimpleGrantedAuthority("SCOPE_settlement.read");
    private static final SimpleGrantedAuthority ADMIN = new SimpleGrantedAuthority("SCOPE_settlement.admin");

    @Test
    void readsOneSettlementAndListsFailures() throws Exception {
        ledgerAnswers(422, """
                {"transactionId": 1, "status": "REJECTED", "reason": "INSUFFICIENT_FUNDS"}""");
        TradeExecuted t = trade("b", "s", 10, 1);
        publish(t);
        awaitStatus(t.tradeId(), SettlementStatus.FAILED);

        mvc.perform(get("/settlements/{id}", t.tradeId()).with(jwt().authorities(READ)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.reason").value("INSUFFICIENT_FUNDS"));
        mvc.perform(get("/settlements").param("status", "FAILED").with(jwt().authorities(READ)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.tradeId == '%s')]".formatted(t.tradeId())).exists());
        mvc.perform(get("/settlements/{id}", "nope").with(jwt().authorities(READ)))
                .andExpect(status().isNotFound());
    }

    @Test
    void retryNeedsAdminScope() throws Exception {
        mvc.perform(post("/settlements/x/retry").with(jwt().authorities(READ))).andExpect(status().isForbidden());
        mvc.perform(post("/settlements/x/retry").with(jwt().authorities(ADMIN))).andExpect(status().isNotFound());
    }

    @Test
    void noTokenIsUnauthorized() throws Exception {
        mvc.perform(get("/settlements")).andExpect(status().isUnauthorized());
    }
}
