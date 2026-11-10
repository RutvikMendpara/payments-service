package com.rutvik.payments.settlement.kafka;

import com.rutvik.payments.settlement.SettlementProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    @Bean
    NewTopic tradesExecuted(SettlementProperties props) {
        return TopicBuilder.name(props.tradesTopic()).partitions(props.tradesPartitions()).build();
    }

    @Bean
    NewTopic tradesDeadLetter(SettlementProperties props) {
        return TopicBuilder.name(props.tradesTopic() + ".DLT").partitions(props.tradesPartitions()).build();
    }

    @Bean
    NewTopic settlementCompleted() {
        return TopicBuilder.name("settlement.completed").partitions(3).build();
    }

    @Bean
    NewTopic settlementFailed() {
        return TopicBuilder.name("settlement.failed").partitions(3).build();
    }

    /**
     * Transient failures (ledger down, DB blip) are retried with exponential backoff on the
     * consumer thread. Bad messages skip the retries. Either way, once we give up the record
     * goes to trades.executed.DLT on the same partition and the consumer moves on.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template, SettlementProperties props,
                                          MeterRegistry meters) {
        DeadLetterPublishingRecoverer dlt = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
        SettlementProperties.Retry r = props.retry();
        ExponentialBackOff backOff = new ExponentialBackOff(r.initialInterval().toMillis(), r.multiplier());
        backOff.setMaxInterval(r.maxInterval().toMillis());
        backOff.setMaxAttempts(r.maxRetries());

        DefaultErrorHandler handler = new DefaultErrorHandler((record, ex) -> {
            meters.counter("settlement.dead_lettered").increment();
            log.error("sending trade message at {}-{}@{} to DLT: {}", record.topic(), record.partition(),
                    record.offset(), ex.getMessage());
            dlt.accept(record, ex);
        }, backOff);
        handler.addNotRetryableExceptions(InvalidTradeException.class, DeserializationException.class);
        return handler;
    }
}
