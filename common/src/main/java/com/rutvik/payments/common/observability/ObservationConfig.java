package com.rutvik.payments.common.observability;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

@Configuration
public class ObservationConfig {

    /**
     * The outbox relay polls every 100ms and Prometheus scrapes every 5s; tracing those buries
     * the traces anyone actually wants to look at. Kafka sends made by the relay are still traced.
     */
    @Bean
    ObservationPredicate skipPollingNoise() {
        return (name, context) -> {
            if (name.equals("tasks.scheduled.execution")) {
                return false;
            }
            if (context instanceof ServerRequestObservationContext server) {
                return !server.getCarrier().getRequestURI().startsWith("/actuator");
            }
            return true;
        };
    }
}
