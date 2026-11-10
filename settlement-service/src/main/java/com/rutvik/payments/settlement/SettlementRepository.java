package com.rutvik.payments.settlement;

import com.rutvik.payments.common.events.TradeExecuted;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class SettlementRepository {

    public static final RowMapper<Settlement> ROW = (rs, i) -> new Settlement(
            rs.getString("trade_id"), rs.getString("symbol"), rs.getLong("price"), rs.getLong("quantity"),
            rs.getString("buyer_id"), rs.getString("seller_id"),
            SettlementStatus.valueOf(rs.getString("status")), rs.getString("reason"), rs.getInt("attempts"),
            rs.getObject("ledger_transaction_id", Long.class),
            instant(rs.getTimestamp("executed_at")), instant(rs.getTimestamp("created_at")),
            instant(rs.getTimestamp("updated_at")));

    private final JdbcClient jdbc;

    public SettlementRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isProcessed(String eventId) {
        return jdbc.sql("select exists (select 1 from processed_events where event_id = :id)")
                .param("id", eventId).query(Boolean.class).single();
    }

    /** Creates the PENDING row the first time we see a trade; returns whatever is stored. */
    public Settlement insertPendingIfAbsent(TradeExecuted trade) {
        jdbc.sql("""
                        insert into settlements (trade_id, symbol, price, quantity, buyer_id, seller_id, status, executed_at)
                        values (:id, :symbol, :price, :qty, :buyer, :seller, 'PENDING', :executedAt)
                        on conflict (trade_id) do nothing
                        """)
                .param("id", trade.tradeId()).param("symbol", trade.symbol())
                .param("price", trade.price()).param("qty", trade.quantity())
                .param("buyer", trade.buyerId()).param("seller", trade.sellerId())
                .param("executedAt", trade.executedAt() == null ? null : Timestamp.from(trade.executedAt()))
                .update();
        return find(trade.tradeId()).orElseThrow();
    }

    /** Moves PENDING to a final state, but only if nobody else already finished this attempt. */
    public boolean complete(Settlement s, SettlementStatus status, String reason, Long ledgerTransactionId) {
        return jdbc.sql("""
                        update settlements
                        set status = :status, reason = :reason, ledger_transaction_id = :txId, updated_at = now()
                        where trade_id = :id and status = 'PENDING' and attempts = :attempts
                        """)
                .param("status", status.name()).param("reason", reason).param("txId", ledgerTransactionId)
                .param("id", s.tradeId()).param("attempts", s.attempts())
                .update() == 1;
    }

    public void markProcessed(String eventId) {
        jdbc.sql("insert into processed_events (event_id) values (:id) on conflict do nothing")
                .param("id", eventId).update();
    }

    public Optional<Settlement> reopenForRetry(String tradeId) {
        int n = jdbc.sql("""
                        update settlements set status = 'PENDING', reason = null, attempts = attempts + 1,
                               updated_at = now()
                        where trade_id = :id and status = 'FAILED'
                        """)
                .param("id", tradeId).update();
        return n == 1 ? find(tradeId) : Optional.empty();
    }

    public Optional<Settlement> find(String tradeId) {
        return jdbc.sql("select * from settlements where trade_id = :id").param("id", tradeId).query(ROW).optional();
    }

    public List<Settlement> list(SettlementStatus status, int limit, int offset) {
        return jdbc.sql("""
                        select * from settlements
                        where (cast(:status as varchar) is null or status = :status)
                        order by updated_at desc, trade_id
                        limit :limit offset :offset
                        """)
                .param("status", status == null ? null : status.name())
                .param("limit", limit).param("offset", offset)
                .query(ROW).list();
    }

    public List<Settlement> stalePending(Duration olderThan, int limit) {
        return jdbc.sql("""
                        select * from settlements
                        where status = 'PENDING' and updated_at < now() - make_interval(secs => :secs)
                        order by updated_at
                        limit :limit
                        """)
                .param("secs", olderThan.toMillis() / 1000.0).param("limit", limit)
                .query(ROW).list();
    }

    public Map<String, Long> countByStatus() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (SettlementStatus s : SettlementStatus.values()) {
            counts.put(s.name(), 0L);
        }
        jdbc.sql("select status, count(*) from settlements group by status")
                .query((rs, i) -> counts.put(rs.getString(1), rs.getLong(2))).list();
        return counts;
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
