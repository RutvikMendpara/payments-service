package com.rutvik.payments.settlement.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rutvik.payments.common.events.TradeExecuted;
import com.rutvik.payments.settlement.SettlementService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class TradeListener {

    private final SettlementService settlements;
    private final ObjectMapper mapper;

    public TradeListener(SettlementService settlements, ObjectMapper mapper) {
        this.settlements = settlements;
        this.mapper = mapper;
    }

    // Values are read as plain strings and parsed here, so an unparseable message is still
    // a string we can forward to the DLT as-is for someone to look at.
    @KafkaListener(topics = "${settlement.trades-topic}", groupId = "settlement-service")
    public void onMessage(String json) {
        TradeExecuted trade;
        try {
            trade = mapper.readValue(json, TradeExecuted.class);
        } catch (JsonProcessingException e) {
            throw new InvalidTradeException("unreadable trade message", e);
        }
        settlements.onTrade(trade);
    }
}
