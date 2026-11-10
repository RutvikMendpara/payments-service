package com.rutvik.payments.ledger;

import com.rutvik.payments.ledger.account.AccountKind;
import com.rutvik.payments.ledger.account.AccountRepository;
import com.rutvik.payments.ledger.posting.Leg;
import com.rutvik.payments.ledger.posting.PostingRequest;
import com.rutvik.payments.ledger.posting.PostingService;
import com.rutvik.payments.ledger.posting.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

import java.util.List;
import java.util.UUID;

/** Real Postgres and Kafka, started once per JVM and shared by every test class. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @ServiceConnection
    static final KafkaContainer kafka = new KafkaContainer("apache/kafka-native:3.9.1");

    static {
        postgres.start();
        kafka.start();
    }

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected AccountRepository accounts;

    @Autowired
    protected PostingService postings;

    @BeforeEach
    void cleanDatabase() {
        jdbc.sql("truncate ledger_entries, transactions, balances, accounts, outbox restart identity cascade").update();
    }

    protected long openAccount(String owner, String asset) {
        return accounts.create(owner, asset, AccountKind.CUSTOMER).orElseThrow();
    }

    protected void fund(long accountId, String asset, long amount) {
        long external = accounts.externalAccount(asset);
        postings.post(UUID.randomUUID().toString(), new PostingRequest(TransactionType.DEPOSIT, "test funding",
                List.of(Leg.debit(external, asset, amount), Leg.credit(accountId, asset, amount))));
    }

    protected long balance(long accountId) {
        return jdbc.sql("select balance from balances where account_id = :id").param("id", accountId)
                .query(Long.class).single();
    }

    protected long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
