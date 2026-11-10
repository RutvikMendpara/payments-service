package com.rutvik.payments.settlement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.rutvik.payments.common.events.TradeExecuted;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.awaitility.Awaitility.await;

/**
 * Real Postgres and Kafka. The ledger and the token endpoint are WireMock, so each test
 * controls exactly what the ledger answers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @ServiceConnection
    static final KafkaContainer kafka = new KafkaContainer("apache/kafka-native:3.9.1");

    protected static final WireMockServer ledger = new WireMockServer(options().dynamicPort());

    static {
        postgres.start();
        kafka.start();
        ledger.start();
    }

    @DynamicPropertySource
    static void ledgerUrls(DynamicPropertyRegistry registry) {
        registry.add("ledger.base-url", ledger::baseUrl);
        registry.add("spring.security.oauth2.client.provider.keycloak.token-uri", () -> ledger.baseUrl() + "/token");
    }

    @Autowired
    protected KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    protected ObjectMapper mapper;

    @Autowired
    protected SettlementRepository settlements;

    @Autowired
    protected SettlementService service;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected CircuitBreakerRegistry breakers;

    @BeforeEach
    void resetLedger() {
        ledger.resetAll();
        ledger.stubFor(post(urlEqualTo("/token")).willReturn(okJson("""
                {"access_token": "test-token", "token_type": "Bearer", "expires_in": 3600}""")));
        breakers.circuitBreaker("ledger").reset();
    }

    protected static void ledgerAnswers(int status, String json) {
        ledger.stubFor(post(urlEqualTo("/postings")).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody(json)));
    }

    protected static void ledgerPosts(long txId) {
        ledgerAnswers(201, """
                {"transactionId": %d, "status": "POSTED", "reason": null, "replayed": false}""".formatted(txId));
    }

    protected static TradeExecuted trade(String buyer, String seller, long price, long qty) {
        return new TradeExecuted("T-" + UUID.randomUUID(), "INFY", price, qty, buyer, seller, Instant.now());
    }

    protected void publish(TradeExecuted trade) throws Exception {
        kafkaTemplate.send("trades.executed", trade.tradeId(), mapper.writeValueAsString(trade)).get();
    }

    protected Settlement awaitStatus(String tradeId, SettlementStatus status) {
        await().atMost(Duration.ofSeconds(20)).until(() ->
                settlements.find(tradeId).map(s -> s.status() == status).orElse(false));
        return settlements.find(tradeId).orElseThrow();
    }

    protected static ConsumerRecord<String, String> consumeOne(String topic, String key) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(250))) {
                    if (key.equals(r.key())) {
                        return r;
                    }
                }
            }
        }
        throw new AssertionError("nothing on " + topic + " with key " + key);
    }
}
