package com.rutvik.payments.ledger;

import com.rutvik.payments.common.outbox.OutboxRelay;
import com.rutvik.payments.ledger.posting.Leg;
import com.rutvik.payments.ledger.posting.PostingRequest;
import com.rutvik.payments.ledger.posting.TransactionType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The relay is disabled in tests, so committing a transfer and *not* publishing is exactly
 * the "crashed after commit, before publish" state. Running the relay afterwards is the restart.
 */
class OutboxTest extends IntegrationTest {

    @Autowired
    OutboxRelay relay;

    @Autowired
    TransactionTemplate tx;

    @Test
    void eventCommittedBeforeCrashIsPublishedAfterRestart() {
        long alice = openAccount("alice", "INR");
        long bob = openAccount("bob", "INR");
        fund(alice, "INR", 5_000);
        String key = "crash-" + UUID.randomUUID();

        postings.post(key, transfer(alice, bob, 1_234));

        // committed, not yet published
        assertThat(count("select count(*) from outbox where published_at is null")).isEqualTo(1);

        try (KafkaConsumer<String, String> consumer = consumer()) {
            assertThat(relay.publishPending()).isEqualTo(1);

            ConsumerRecord<String, String> record = pollFor(consumer, key);
            assertThat(record.value().replace(" ", "")).contains("\"amount\":1234").contains("\"fromAccountId\":" + alice);
            assertThat(record.headers().lastHeader("eventId")).isNotNull();
            assertThat(new String(record.headers().lastHeader("eventType").value(), StandardCharsets.UTF_8))
                    .isEqualTo("TransferCompleted");
        }
        assertThat(count("select count(*) from outbox where published_at is null")).isZero();
        assertThat(relay.publishPending()).isZero();
    }

    @Test
    void failedPublishLeavesRowsForNextRun() {
        long alice = openAccount("alice", "INR");
        long bob = openAccount("bob", "INR");
        fund(alice, "INR", 5_000);
        postings.post("down-1", transfer(alice, bob, 10));
        postings.post("down-2", transfer(alice, bob, 20));

        // Broker unreachable: the batch fails and nothing is marked as sent.
        KafkaTemplate<String, String> dead = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1",
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 500,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class)));
        OutboxRelay brokenRelay = new OutboxRelay(jdbc, dead, tx, 100, new SimpleMeterRegistry());
        assertThatThrownBy(brokenRelay::publishPending).isInstanceOf(IllegalStateException.class);
        assertThat(count("select count(*) from outbox where published_at is null")).isEqualTo(2);

        assertThat(relay.publishPending()).isEqualTo(2);
        assertThat(count("select count(*) from outbox where published_at is null")).isZero();
    }

    @Test
    void rolledBackPostingLeavesNoEvent() {
        long alice = openAccount("alice", "INR");
        long bob = openAccount("bob", "INR");
        postings.post("broke", transfer(alice, bob, 10)); // rejected, nothing posted
        assertThat(count("select count(*) from outbox")).isZero();
    }

    private static PostingRequest transfer(long from, long to, long amount) {
        return new PostingRequest(TransactionType.TRANSFER, null,
                List.of(Leg.debit(from, "INR", amount), Leg.credit(to, "INR", amount)));
    }

    private static KafkaConsumer<String, String> consumer() {
        KafkaConsumer<String, String> c = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "outbox-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new StringDeserializer());
        c.subscribe(List.of("transfer.completed"));
        return c;
    }

    private static ConsumerRecord<String, String> pollFor(KafkaConsumer<String, String> consumer, String key) {
        long deadline = System.currentTimeMillis() + 15_000;
        List<ConsumerRecord<String, String>> seen = new ArrayList<>();
        while (System.currentTimeMillis() < deadline) {
            consumer.poll(Duration.ofMillis(250)).forEach(seen::add);
            for (ConsumerRecord<String, String> r : seen) {
                if (key.equals(r.key())) {
                    return r;
                }
            }
        }
        throw new AssertionError("no record with key " + key + " on transfer.completed");
    }
}
