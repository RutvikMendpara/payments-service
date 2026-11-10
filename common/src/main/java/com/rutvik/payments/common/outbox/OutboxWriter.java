package com.rutvik.payments.common.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Writes an event row in the caller's transaction. If the business change rolls back,
 * so does the event; if it commits, the relay is guaranteed to see the row.
 */
public class OutboxWriter {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public OutboxWriter(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID write(String topic, String key, String eventType, Object payload) {
        UUID eventId = UUID.randomUUID();
        jdbc.sql("""
                insert into outbox (event_id, topic, event_key, event_type, payload)
                values (:eventId, :topic, :key, :type, cast(:payload as jsonb))
                """)
                .param("eventId", eventId)
                .param("topic", topic)
                .param("key", key)
                .param("type", eventType)
                .param("payload", toJson(payload))
                .update();
        return eventId;
    }

    private String toJson(Object payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("event payload is not serialisable", e);
        }
    }
}
