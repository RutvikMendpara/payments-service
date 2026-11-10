package com.rutvik.payments.common.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Polls unpublished outbox rows and sends them to Kafka.
 *
 * Delivery is at-least-once: if we crash after Kafka acks but before published_at is
 * committed, the rows are sent again on the next run. Consumers dedupe on the eventId header.
 * FOR UPDATE SKIP LOCKED lets several instances run the relay without double-sending a batch.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final int batchSize;
    private final Counter published;

    public OutboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                       int batchSize, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.tx = tx;
        this.batchSize = batchSize;
        this.published = Counter.builder("outbox.published").register(meters);
        Gauge.builder("outbox.pending", this, OutboxRelay::pendingCount).register(meters);
    }

    /** Drains the outbox. Returns how many events were sent. */
    public int publishPending() {
        int total = 0;
        int sent;
        do {
            Integer n = tx.execute(status -> publishBatch());
            sent = n == null ? 0 : n;
            total += sent;
        } while (sent == batchSize);
        return total;
    }

    private int publishBatch() {
        List<OutboxRow> rows = jdbc.sql("""
                        select id, event_id, topic, event_key, event_type, payload::text as payload
                        from outbox
                        where published_at is null
                        order by id
                        limit :limit
                        for update skip locked
                        """)
                .param("limit", batchSize)
                .query((rs, i) -> new OutboxRow(
                        rs.getLong("id"),
                        rs.getObject("event_id", UUID.class),
                        rs.getString("topic"),
                        rs.getString("event_key"),
                        rs.getString("event_type"),
                        rs.getString("payload")))
                .list();
        if (rows.isEmpty()) {
            return 0;
        }

        List<CompletableFuture<SendResult<String, String>>> sends = new ArrayList<>(rows.size());
        for (OutboxRow row : rows) {
            ProducerRecord<String, String> record = new ProducerRecord<>(row.topic(), row.key(), row.payload());
            record.headers().add("eventId", row.eventId().toString().getBytes(StandardCharsets.UTF_8));
            record.headers().add("eventType", row.eventType().getBytes(StandardCharsets.UTF_8));
            sends.add(kafka.send(record));
        }
        // Any failure throws and rolls back, so none of the batch is marked published.
        try {
            CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("kafka publish failed, batch will be retried", e);
        }

        jdbc.sql("update outbox set published_at = now() where id in (:ids)")
                .param("ids", rows.stream().map(OutboxRow::id).toList())
                .update();
        published.increment(rows.size());
        log.debug("published {} outbox events", rows.size());
        return rows.size();
    }

    private double pendingCount() {
        try {
            return jdbc.sql("select count(*) from outbox where published_at is null").query(Long.class).single();
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    private record OutboxRow(long id, UUID eventId, String topic, String key, String eventType, String payload) {
    }
}
