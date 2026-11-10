package com.rutvik.payments.ledger.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopics {

    @Bean
    NewTopic transferCompleted() {
        return TopicBuilder.name("transfer.completed").partitions(3).build();
    }
}
