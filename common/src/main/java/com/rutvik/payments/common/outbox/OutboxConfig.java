package com.rutvik.payments.common.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class OutboxConfig {

    @Bean
    OutboxWriter outboxWriter(JdbcClient jdbc, ObjectMapper mapper) {
        return new OutboxWriter(jdbc, mapper);
    }

    @Bean
    OutboxRelay outboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                            @Value("${outbox.batch-size:200}") int batchSize, MeterRegistry meters) {
        return new OutboxRelay(jdbc, kafka, tx, batchSize, meters);
    }

    /**
     * The polling relay can be switched off, e.g. in tests that simulate a crash before
     * publish, or when Debezium reads the outbox from the WAL instead.
     */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
    static class Poller {

        private static final Logger log = LoggerFactory.getLogger(Poller.class);
        private final OutboxRelay relay;

        Poller(OutboxRelay relay) {
            this.relay = relay;
        }

        @Scheduled(fixedDelayString = "${outbox.poll-interval:100ms}")
        void poll() {
            try {
                relay.publishPending();
            } catch (RuntimeException e) {
                log.warn("outbox relay run failed, will retry: {}", e.getMessage());
            }
        }
    }
}
