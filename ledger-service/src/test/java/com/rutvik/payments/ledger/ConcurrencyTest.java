package com.rutvik.payments.ledger;

import com.rutvik.payments.common.outbox.OutboxWriter;
import com.rutvik.payments.ledger.posting.Leg;
import com.rutvik.payments.ledger.posting.PostingRequest;
import com.rutvik.payments.ledger.posting.PostingResult;
import com.rutvik.payments.ledger.posting.PostingService;
import com.rutvik.payments.ledger.posting.PostingStatus;
import com.rutvik.payments.ledger.posting.TransactionType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrencyTest extends IntegrationTest {

    @Autowired
    TransactionTemplate tx;

    @Autowired
    OutboxWriter outbox;

    @Test
    void fiftyParallelTransfersNeverOverdraw() throws Exception {
        long source = openAccount("source", "INR");
        fund(source, "INR", 3_000);
        List<Long> targets = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            targets.add(openAccount("target-" + i, "INR"));
        }

        // Only 30 of the 50 can be paid; the rest must be rejected, none may overdraw.
        List<PostingResult> results = runTogether(50, i -> postings.post("par-" + i, transfer(source, targets.get(i), 100)));

        long posted = results.stream().filter(PostingResult::posted).count();
        long rejected = results.stream().filter(r -> r.status() == PostingStatus.REJECTED).count();
        assertThat(posted).isEqualTo(30);
        assertThat(rejected).isEqualTo(20);
        assertThat(results).filteredOn(r -> !r.posted()).allMatch(r -> r.reason().equals("INSUFFICIENT_FUNDS"));
        assertThat(balance(source)).isZero();
        long received = targets.stream().mapToLong(this::balance).sum();
        assertThat(received).isEqualTo(3_000);
        assertThat(count("select count(*) from transactions where status = 'POSTED' and type = 'TRANSFER'")).isEqualTo(30);
    }

    @Test
    void fiftyParallelTransfersAllLandWhenFunded() throws Exception {
        long source = openAccount("source", "INR");
        long sink = openAccount("sink", "INR");
        fund(source, "INR", 1_000_000);

        // Everyone fights over the same two balance rows; optimistic retries must get all of them through.
        List<PostingResult> results = runTogether(50, i -> postings.post("hot-" + i, transfer(source, sink, 1_000 + i)));

        assertThat(results).allMatch(PostingResult::posted);
        long expected = 0;
        for (int i = 0; i < 50; i++) {
            expected += 1_000 + i;
        }
        assertThat(balance(sink)).isEqualTo(expected);
        assertThat(balance(source)).isEqualTo(1_000_000 - expected);
        assertThat(jdbc.sql("select version from balances where account_id = :id").param("id", sink)
                .query(Long.class).single()).isEqualTo(50);
    }

    @Test
    void sameKeySentTenTimesConcurrentlyPostsOnce() throws Exception {
        long alice = openAccount("alice", "INR");
        long bob = openAccount("bob", "INR");
        fund(alice, "INR", 10_000);

        List<PostingResult> results = runTogether(10, i -> postings.post("same-key", transfer(alice, bob, 700)));

        assertThat(results).allMatch(PostingResult::posted);
        assertThat(results.stream().map(PostingResult::transactionId).distinct()).hasSize(1);
        assertThat(results.stream().filter(r -> !r.replayed())).hasSize(1);
        assertThat(balance(bob)).isEqualTo(700);
        assertThat(count("select count(*) from transactions where idempotency_key = 'same-key'")).isEqualTo(1);
        assertThat(count("select count(*) from outbox where event_type = 'TransferCompleted'")).isEqualTo(1);
    }

    @Test
    void pessimisticModeQueuesOnTheRowLockInsteadOfRetrying() throws Exception {
        long source = openAccount("source", "INR");
        long sink = openAccount("sink", "INR");
        fund(source, "INR", 1_000_000);
        PostingService pessimistic = new PostingService(jdbc, tx, accounts, outbox, new SimpleMeterRegistry(), 1,
                PostingService.LockingMode.PESSIMISTIC);

        // max-attempts = 1: any optimistic conflict would surface as an error here.
        List<PostingResult> results = runTogether(50, i -> pessimistic.post("lock-" + i, transfer(source, sink, 10)));

        assertThat(results).allMatch(PostingResult::posted);
        assertThat(balance(sink)).isEqualTo(500);
        assertThat(balance(source)).isEqualTo(1_000_000 - 500);
    }

    private static PostingRequest transfer(long from, long to, long amount) {
        return new PostingRequest(TransactionType.TRANSFER, null,
                List.of(Leg.debit(from, "INR", amount), Leg.credit(to, "INR", amount)));
    }

    interface Task<T> {
        T run(int i) throws Exception;
    }

    /** Releases all tasks at the same moment so they really overlap. */
    private static <T> List<T> runTogether(int n, Task<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int idx = i;
                Callable<T> c = () -> {
                    start.await();
                    return task.run(idx);
                };
                futures.add(pool.submit(c));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
