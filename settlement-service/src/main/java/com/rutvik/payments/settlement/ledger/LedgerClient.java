package com.rutvik.payments.settlement.ledger;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Calls ledger-service. Retry wraps the circuit breaker (resilience4j's default aspect order),
 * so a short blip is retried in place, while a ledger that keeps failing trips the breaker
 * and calls fail fast until it recovers. Connect/read timeouts are set on the RestClient.
 */
@Component
public class LedgerClient {

    private final RestClient rest;

    public LedgerClient(RestClient ledgerRestClient) {
        this.rest = ledgerRestClient;
    }

    public record LedgerLeg(String ownerId, String asset, String direction, long amount) {
    }

    @Retry(name = "ledger")
    @CircuitBreaker(name = "ledger")
    public LedgerResult post(String idempotencyKey, String type, String reference, List<LedgerLeg> legs) {
        Map<String, Object> body = Map.of("type", type, "reference", reference, "legs", legs);
        return rest.post()
                .uri("/postings")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, response) -> {
                    HttpStatusCode status = response.getStatusCode();
                    // 201 posted, 422 rejected for funds/holdings: both are final answers.
                    if (status.value() == 201 || status.value() == 422) {
                        return response.bodyTo(LedgerResult.class);
                    }
                    String text = StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8);
                    if (status.is4xxClientError()) {
                        throw new LedgerRequestRejectedException(status.value(), text);
                    }
                    throw new LedgerUnavailableException("ledger returned " + status.value());
                });
    }
}
