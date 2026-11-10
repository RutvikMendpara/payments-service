package com.rutvik.payments.simulator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rutvik.payments.common.events.TradeExecuted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Publishes TradeExecuted events the same way the matching engine does: JSON on
 * trades.executed, keyed by trade id. A few percent are sent twice to exercise dedupe.
 */
@Component
class Simulator implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(Simulator.class);

    private final SimulatorProperties props;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;

    Simulator(SimulatorProperties props, KafkaTemplate<String, String> kafka, ObjectMapper mapper) {
        this.props = props;
        this.kafka = kafka;
        this.mapper = mapper;
    }

    static String trader(int i) {
        return "trader-" + i;
    }

    @Override
    public void run(String... args) throws Exception {
        if (props.seed()) {
            new LedgerSeeder(props).seed();
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long pauseNanos = props.ratePerSecond() > 0 ? 1_000_000_000L / props.ratePerSecond() : 0;
        long next = System.nanoTime();
        int duplicates = 0;

        for (int i = 0; i < props.trades(); i++) {
            int buyer = random.nextInt(props.traders());
            int seller = (buyer + 1 + random.nextInt(props.traders() - 1)) % props.traders();
            String symbol = props.symbols().get(random.nextInt(props.symbols().size()));
            TradeExecuted trade = new TradeExecuted(
                    UUID.randomUUID().toString(), symbol,
                    random.nextLong(100_00, 5_000_00),   // ₹100 - ₹5000 per share, in paise
                    random.nextLong(1, 50),
                    trader(buyer), trader(seller), Instant.now());
            String json = mapper.writeValueAsString(trade);
            kafka.send("trades.executed", trade.tradeId(), json);
            if (random.nextInt(100) < props.duplicatePercent()) {
                kafka.send("trades.executed", trade.tradeId(), json);
                duplicates++;
            }
            if (pauseNanos > 0) {
                next += pauseNanos;
                long sleep = next - System.nanoTime();
                if (sleep > 0) {
                    Thread.sleep(sleep / 1_000_000, (int) (sleep % 1_000_000));
                }
            }
        }
        kafka.flush();
        log.info("published {} trades ({} sent twice)", props.trades(), duplicates);
    }
}
