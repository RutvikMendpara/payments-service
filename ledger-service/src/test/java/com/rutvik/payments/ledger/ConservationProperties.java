package com.rutvik.payments.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rutvik.payments.common.outbox.OutboxWriter;
import com.zaxxer.hikari.HikariDataSource;
import com.rutvik.payments.ledger.account.AccountKind;
import com.rutvik.payments.ledger.account.AccountRepository;
import com.rutvik.payments.ledger.posting.Direction;
import com.rutvik.payments.ledger.posting.Leg;
import com.rutvik.payments.ledger.posting.PostingRequest;
import com.rutvik.payments.ledger.posting.PostingResult;
import com.rutvik.payments.ledger.posting.PostingService;
import com.rutvik.payments.ledger.posting.TransactionType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeContainer;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Random sequences of transfers, DvP trades and replayed requests against a real Postgres.
 * Whatever the sequence, no money or shares are created or destroyed.
 */
class ConservationProperties {

    private static final int OWNERS = 5;
    private static final long START_CASH = 1_000_000;
    private static final long START_SHARES = 100;

    private static PostgreSQLContainer<?> postgres;
    private static JdbcClient jdbc;
    private static AccountRepository accounts;
    private static PostingService postings;

    sealed interface Op permits Transfer, Trade, Replay {
    }

    record Transfer(int from, int to, long amount) implements Op {
    }

    record Trade(int buyer, int seller, long price, long qty) implements Op {
    }

    /** Re-send an earlier request with its original key, like a client retry or Kafka redelivery. */
    record Replay(int index) implements Op {
    }

    @BeforeContainer
    static void startDatabase() {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(postgres.getJdbcUrl());
        ds.setUsername(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        Flyway.configure().dataSource(ds).load().migrate();

        jdbc = JdbcClient.create(ds);
        accounts = new AccountRepository(jdbc);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        postings = new PostingService(jdbc, new TransactionTemplate(new JdbcTransactionManager(ds)), accounts,
                new OutboxWriter(jdbc, mapper), new SimpleMeterRegistry(), 40);
    }

    @AfterContainer
    static void stopDatabase() {
        postgres.stop();
    }

    @Property(tries = 200)
    void totalsNeverChange(@ForAll("operations") List<Op> ops) {
        jdbc.sql("truncate ledger_entries, transactions, balances, accounts, outbox restart identity cascade").update();
        for (int i = 0; i < OWNERS; i++) {
            fund(owner(i), "INR", START_CASH);
            fund(owner(i), "INFY", START_SHARES);
        }

        List<Sent> sent = new ArrayList<>();
        for (int i = 0; i < ops.size(); i++) {
            Op op = ops.get(i);
            if (op instanceof Replay r && !sent.isEmpty()) {
                Sent original = sent.get(r.index() % sent.size());
                Map<Long, Long> before = balances();
                PostingResult again = postings.post(original.key(), original.request());
                assertThat(again.replayed()).isTrue();
                assertThat(again.transactionId()).isEqualTo(original.result().transactionId());
                assertThat(balances()).isEqualTo(before);
            } else if (!(op instanceof Replay)) {
                String key = "op-" + i;
                PostingRequest request = toRequest(op);
                sent.add(new Sent(key, request, postings.post(key, request)));
            }
        }

        assertThat(customerTotal("INR")).isEqualTo(OWNERS * START_CASH);
        assertThat(customerTotal("INFY")).isEqualTo(OWNERS * START_SHARES);
        assertThat(jdbc.sql("select coalesce(sum(balance), 0) from balances").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
                select count(*) from balances b join accounts a on a.id = b.account_id
                where a.kind = 'CUSTOMER' and b.balance < 0""").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
                select count(*) from (
                  select transaction_id, asset from ledger_entries group by transaction_id, asset
                  having sum(case when direction = 'DEBIT' then amount else 0 end)
                      <> sum(case when direction = 'CREDIT' then amount else 0 end)) x""")
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
                select count(*) from balances b
                left join (select account_id,
                                  sum(case when direction = 'CREDIT' then amount else -amount end) as net
                           from ledger_entries group by account_id) e on e.account_id = b.account_id
                where b.balance <> coalesce(e.net, 0)""").query(Long.class).single()).isZero();
    }

    @Provide
    Arbitrary<List<Op>> operations() {
        Arbitrary<Integer> who = Arbitraries.integers().between(0, OWNERS - 1);
        Arbitrary<Op> transfer = Combinators.combine(who, who, Arbitraries.longs().between(1, 600_000))
                .filter((a, b, amt) -> !a.equals(b))
                .as(Transfer::new);
        Arbitrary<Op> trade = Combinators.combine(who, who, Arbitraries.longs().between(1, 20_000),
                        Arbitraries.longs().between(1, 80))
                .filter((b, s, p, q) -> !b.equals(s))
                .as(Trade::new);
        Arbitrary<Op> replay = Arbitraries.integers().between(0, 1_000).map(Replay::new);
        return Arbitraries.frequencyOf(
                        net.jqwik.api.Tuple.of(4, transfer),
                        net.jqwik.api.Tuple.of(4, trade),
                        net.jqwik.api.Tuple.of(2, replay))
                .list().ofMaxSize(40);
    }

    private static PostingRequest toRequest(Op op) {
        return switch (op) {
            case Transfer t -> new PostingRequest(TransactionType.TRANSFER, null, List.of(
                    new Leg(null, owner(t.from()), "INR", Direction.DEBIT, t.amount()),
                    new Leg(null, owner(t.to()), "INR", Direction.CREDIT, t.amount())));
            case Trade t -> {
                long cash = t.price() * t.qty();
                yield new PostingRequest(TransactionType.TRADE_SETTLEMENT, null, List.of(
                        new Leg(null, owner(t.buyer()), "INR", Direction.DEBIT, cash),
                        new Leg(null, owner(t.seller()), "INR", Direction.CREDIT, cash),
                        new Leg(null, owner(t.seller()), "INFY", Direction.DEBIT, t.qty()),
                        new Leg(null, owner(t.buyer()), "INFY", Direction.CREDIT, t.qty())));
            }
            case Replay r -> throw new IllegalArgumentException();
        };
    }

    private static void fund(String owner, String asset, long amount) {
        long account = accounts.findOrCreate(owner, asset, AccountKind.CUSTOMER);
        long external = accounts.externalAccount(asset);
        postings.post("fund-" + owner + "-" + asset, new PostingRequest(TransactionType.DEPOSIT, null,
                List.of(Leg.debit(external, asset, amount), Leg.credit(account, asset, amount))));
    }

    private static long customerTotal(String asset) {
        return jdbc.sql("""
                select coalesce(sum(b.balance), 0) from balances b join accounts a on a.id = b.account_id
                where a.kind = 'CUSTOMER' and a.asset = :asset""").param("asset", asset).query(Long.class).single();
    }

    private static Map<Long, Long> balances() {
        Map<Long, Long> m = new HashMap<>();
        jdbc.sql("select account_id, balance from balances").query((rs, i) -> m.put(rs.getLong(1), rs.getLong(2))).list();
        return m;
    }

    private static String owner(int i) {
        return "owner-" + i;
    }

    private record Sent(String key, PostingRequest request, PostingResult result) {
    }
}
