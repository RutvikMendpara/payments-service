package com.rutvik.payments.settlement.fx;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class FxRepository {

    private static final RowMapper<FxTransfer> ROW = (rs, i) -> new FxTransfer(
            rs.getObject("id", UUID.class), rs.getString("idempotency_key"),
            rs.getString("from_owner"), rs.getString("to_owner"),
            rs.getString("from_currency"), rs.getString("to_currency"),
            rs.getLong("source_amount"), rs.getLong("target_amount"), rs.getLong("rate_micros"),
            FxStatus.valueOf(rs.getString("status")), rs.getString("reason"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());

    private final JdbcClient jdbc;

    FxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Long> rate(String base, String quote) {
        return jdbc.sql("select rate_micros from fx_rates where base = :b and quote = :q")
                .param("b", base).param("q", quote).query(Long.class).optional();
    }

    /** Inserts the saga, or returns the one already started under this key. */
    FxTransfer startOrGet(FxTransfer t) {
        jdbc.sql("""
                        insert into fx_transfers (id, idempotency_key, from_owner, to_owner, from_currency, to_currency,
                                                  source_amount, target_amount, rate_micros, status)
                        values (:id, :key, :fromOwner, :toOwner, :fromCcy, :toCcy, :src, :tgt, :rate, 'STARTED')
                        on conflict (idempotency_key) do nothing
                        """)
                .param("id", t.id()).param("key", t.idempotencyKey())
                .param("fromOwner", t.fromOwner()).param("toOwner", t.toOwner())
                .param("fromCcy", t.fromCurrency()).param("toCcy", t.toCurrency())
                .param("src", t.sourceAmount()).param("tgt", t.targetAmount()).param("rate", t.rateMicros())
                .update();
        return byKey(t.idempotencyKey()).orElseThrow();
    }

    /** Compare-and-set on status, so two workers resuming the same saga can't both advance it. */
    boolean advance(UUID id, FxStatus from, FxStatus to, String reason) {
        return jdbc.sql("""
                        update fx_transfers set status = :to, reason = coalesce(:reason, reason), updated_at = now()
                        where id = :id and status = :from
                        """)
                .param("to", to.name()).param("reason", reason).param("id", id).param("from", from.name())
                .update() == 1;
    }

    Optional<FxTransfer> find(UUID id) {
        return jdbc.sql("select * from fx_transfers where id = :id").param("id", id).query(ROW).optional();
    }

    Optional<FxTransfer> byKey(String key) {
        return jdbc.sql("select * from fx_transfers where idempotency_key = :k").param("k", key).query(ROW).optional();
    }

    List<FxTransfer> unfinished(Duration olderThan) {
        return jdbc.sql("""
                        select * from fx_transfers
                        where status in ('STARTED', 'DEBITED', 'COMPENSATING')
                          and updated_at < now() - make_interval(secs => :secs)
                        order by updated_at limit 100
                        """)
                .param("secs", olderThan.toMillis() / 1000.0).query(ROW).list();
    }
}
