package com.rutvik.payments.simulator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.Map;

/** Opens a cash and a holding account per trader and funds them, so trades have something to settle. */
class LedgerSeeder {

    private static final Logger log = LoggerFactory.getLogger(LedgerSeeder.class);

    private final SimulatorProperties props;
    private final RestClient ledger;

    LedgerSeeder(SimulatorProperties props) {
        this.props = props;
        String token = fetchToken();
        this.ledger = RestClient.builder()
                .baseUrl(props.ledgerUrl())
                .defaultHeader("Authorization", "Bearer " + token)
                .build();
    }

    void seed() {
        for (int i = 0; i < props.traders(); i++) {
            String trader = Simulator.trader(i);
            fund(trader, "INR", props.startingCash());
            for (String symbol : props.symbols()) {
                fund(trader, symbol, props.startingShares());
            }
        }
        log.info("seeded {} traders", props.traders());
    }

    private void fund(String owner, String asset, long amount) {
        Map<?, ?> account = ledger.post().uri("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("ownerId", owner, "asset", asset))
                .exchange((req, res) -> res.getStatusCode().isSameCodeAs(HttpStatusCode.valueOf(409))
                        ? null : res.bodyTo(Map.class));
        if (account == null) {
            return; // already seeded on an earlier run
        }
        ledger.post().uri("/accounts/{id}/deposits", account.get("id"))
                .header("Idempotency-Key", "seed-" + owner + "-" + asset)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("amount", amount, "reference", "simulator seed"))
                .retrieve().toBodilessEntity();
    }

    private String fetchToken() {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", props.clientId());
        form.add("client_secret", props.clientSecret());
        Map<?, ?> body = RestClient.create().post().uri(props.tokenUri())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form).retrieve().body(Map.class);
        return (String) body.get("access_token");
    }
}
