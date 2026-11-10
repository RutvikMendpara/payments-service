package com.rutvik.payments.ledger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SecurityTest extends IntegrationTest {

    @Autowired
    WebApplicationContext context;

    MockMvc plain;

    @BeforeEach
    void setUp() {
        plain = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void noTokenIsUnauthorized() throws Exception {
        plain.perform(get("/accounts")).andExpect(status().isUnauthorized());
    }

    @Test
    void readScopeCannotMoveMoney() throws Exception {
        plain.perform(post("/transfers").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_ledger.read")))
                        .header("Idempotency-Key", "x").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromAccountId\": 1, \"toAccountId\": 2, \"amount\": 1}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void writeScopeCannotUseRawPostings() throws Exception {
        plain.perform(post("/postings").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_ledger.write")))
                        .header("Idempotency-Key", "x").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void healthIsPublic() throws Exception {
        plain.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
