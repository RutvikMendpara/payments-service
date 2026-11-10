package com.rutvik.payments.ledger.posting;

import com.rutvik.payments.common.outbox.OutboxWriter;
import com.rutvik.payments.ledger.account.Account;
import com.rutvik.payments.ledger.account.AccountKind;
import com.rutvik.payments.ledger.account.AccountRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The only way money moves. Every call either commits one balanced transaction or nothing.
 *
 * Concurrency (ledger.posting.locking):
 *  - OPTIMISTIC (default): balances are read, checked, then updated with "where version = ?".
 *    If someone else got there first the update hits 0 rows, the DB transaction rolls back and
 *    we retry from the top with jittered backoff. Cheap when accounts rarely collide.
 *  - PESSIMISTIC: balances are read with SELECT ... FOR UPDATE, so writers to the same account
 *    queue on the row lock instead of failing and retrying. Better for a few very hot accounts.
 * Either way account rows are locked/updated in id order, so two postings touching the same
 * pair of accounts can't deadlock.
 *
 * Idempotency: the key is a unique column on transactions. A repeat with the same body gets
 * the stored result back; a repeat with a different body is a 409. Rejections are stored too,
 * so a retried request that was rejected stays rejected rather than silently succeeding later.
 */
@Service
public class PostingService {

    private static final Set<String> CURRENCIES = Set.of("INR", "USD", "EUR", "GBP");

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final AccountRepository accounts;
    private final OutboxWriter outbox;
    private final int maxAttempts;
    private final LockingMode locking;
    private final Counter conflicts;
    private final MeterRegistry meters;

    public enum LockingMode { OPTIMISTIC, PESSIMISTIC }

    public PostingService(JdbcClient jdbc, TransactionTemplate tx, AccountRepository accounts, OutboxWriter outbox,
                          MeterRegistry meters, @Value("${ledger.posting.max-attempts:40}") int maxAttempts,
                          @Value("${ledger.posting.locking:OPTIMISTIC}") LockingMode locking) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.accounts = accounts;
        this.outbox = outbox;
        this.meters = meters;
        this.maxAttempts = maxAttempts;
        this.locking = locking;
        this.conflicts = Counter.builder("ledger.posting.conflicts")
                .description("optimistic lock conflicts that caused a retry").register(meters);
    }

    public PostingResult post(String idempotencyKey, PostingRequest request) {
        validate(idempotencyKey, request);
        String hash = sha256(request.canonical());

        Optional<StoredTransaction> existing = findByKey(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), hash);
        }

        for (int attempt = 1; ; attempt++) {
            try {
                PostingResult result = tx.execute(s -> apply(idempotencyKey, hash, request));
                meters.counter("ledger.postings", "type", request.type().name(), "status", result.status().name())
                        .increment();
                return result;
            } catch (OptimisticLockConflict e) {
                conflicts.increment();
                if (attempt >= maxAttempts) {
                    throw new LedgerException(HttpStatus.SERVICE_UNAVAILABLE, "CONTENTION",
                            "too much contention on account " + e.accountId + ", retry later");
                }
                backoff(attempt);
            } catch (DuplicateKeyException e) {
                // A concurrent request with the same key committed between our lookup and insert.
                return replay(findByKey(idempotencyKey).orElseThrow(), hash);
            }
        }
    }

    public Optional<PostingResult> findResult(String idempotencyKey) {
        return findByKey(idempotencyKey).map(StoredTransaction::result);
    }

    public Optional<PostingResult> findResult(long transactionId) {
        return jdbc.sql("select * from transactions where id = :id").param("id", transactionId)
                .query(PostingService::mapStored).optional().map(StoredTransaction::result);
    }

    private PostingResult apply(String key, String hash, PostingRequest request) {
        List<ResolvedLeg> legs = new ArrayList<>(request.legs().size());
        for (Leg leg : request.legs()) {
            Optional<Account> account = resolve(leg);
            if (account.isEmpty()) {
                return insertTransaction(key, hash, request, PostingStatus.REJECTED, "ACCOUNT_NOT_FOUND");
            }
            Account a = account.get();
            if (!a.asset().equals(leg.asset())) {
                throw LedgerException.invalid("account " + a.id() + " holds " + a.asset() + ", not " + leg.asset());
            }
            if (!a.active()) {
                return insertTransaction(key, hash, request, PostingStatus.REJECTED, "ACCOUNT_CLOSED");
            }
            legs.add(new ResolvedLeg(a.id(), leg));
        }

        // Net movement per account, in id order for lock ordering.
        Map<Long, Long> delta = new TreeMap<>();
        for (ResolvedLeg leg : legs) {
            delta.merge(leg.accountId(), leg.leg().direction().signed(leg.leg().amount()), Math::addExact);
        }
        Map<Long, BalanceRow> balances = loadBalances(delta.keySet());

        // Check in leg order so the reason names the first side that can't pay.
        for (ResolvedLeg leg : legs) {
            BalanceRow b = balances.get(leg.accountId());
            if (!b.allowNegative() && b.balance() + delta.get(leg.accountId()) < 0) {
                String reason = CURRENCIES.contains(leg.leg().asset()) ? "INSUFFICIENT_FUNDS" : "INSUFFICIENT_SECURITIES";
                return insertTransaction(key, hash, request, PostingStatus.REJECTED, reason);
            }
        }

        PostingResult result = insertTransaction(key, hash, request, PostingStatus.POSTED, null);
        insertEntries(result.transactionId(), legs);
        for (Map.Entry<Long, Long> d : delta.entrySet()) {
            BalanceRow b = balances.get(d.getKey());
            int updated = jdbc.sql("""
                            update balances set balance = balance + :delta, version = version + 1
                            where account_id = :id and version = :version
                            """)
                    .param("delta", d.getValue()).param("id", d.getKey()).param("version", b.version())
                    .update();
            if (updated == 0) {
                throw new OptimisticLockConflict(d.getKey());
            }
        }

        if (request.type() == TransactionType.TRANSFER) {
            outbox.write("transfer.completed", key, "TransferCompleted", transferEvent(result, legs));
        }
        return result;
    }

    private Optional<Account> resolve(Leg leg) {
        if (leg.accountId() != null) {
            return accounts.findById(leg.accountId());
        }
        Optional<Account> existing = accounts.findByOwnerAndAsset(leg.ownerId(), leg.asset());
        if (existing.isPresent() || leg.direction() == Direction.DEBIT) {
            return existing;
        }
        // Receiving an asset for the first time (e.g. a buyer's first INFY shares) opens the account.
        long id = accounts.findOrCreate(leg.ownerId(), leg.asset(), AccountKind.CUSTOMER);
        return accounts.findById(id);
    }

    private Map<Long, BalanceRow> loadBalances(Set<Long> ids) {
        Map<Long, BalanceRow> rows = new HashMap<>();
        String sql = "select account_id, balance, allow_negative, version from balances where account_id in (:ids)"
                + (locking == LockingMode.PESSIMISTIC ? " order by account_id for update" : "");
        jdbc.sql(sql)
                .param("ids", ids)
                .query((rs, i) -> new BalanceRow(rs.getLong(1), rs.getLong(2), rs.getBoolean(3), rs.getLong(4)))
                .list()
                .forEach(r -> rows.put(r.accountId(), r));
        return rows;
    }

    private PostingResult insertTransaction(String key, String hash, PostingRequest request,
                                            PostingStatus status, String reason) {
        return jdbc.sql("""
                        insert into transactions (idempotency_key, request_hash, type, status, reason, reference)
                        values (:key, :hash, :type, :status, :reason, :reference)
                        returning *
                        """)
                .param("key", key).param("hash", hash).param("type", request.type().name())
                .param("status", status.name()).param("reason", reason).param("reference", request.reference())
                .query(PostingService::mapStored).single().result();
    }

    private void insertEntries(long transactionId, List<ResolvedLeg> legs) {
        for (ResolvedLeg leg : legs) {
            jdbc.sql("""
                            insert into ledger_entries (transaction_id, account_id, asset, direction, amount)
                            values (:tx, :account, :asset, :direction, :amount)
                            """)
                    .param("tx", transactionId).param("account", leg.accountId())
                    .param("asset", leg.leg().asset()).param("direction", leg.leg().direction().name())
                    .param("amount", leg.leg().amount())
                    .update();
        }
    }

    private Optional<StoredTransaction> findByKey(String key) {
        return jdbc.sql("select * from transactions where idempotency_key = :key").param("key", key)
                .query(PostingService::mapStored).optional();
    }

    private static PostingResult replay(StoredTransaction stored, String hash) {
        if (!stored.requestHash().equals(hash)) {
            throw new LedgerException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                    "idempotency key was already used with a different request");
        }
        return stored.result().asReplay();
    }

    private static void validate(String key, PostingRequest request) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw LedgerException.invalid("an Idempotency-Key of 1-128 characters is required");
        }
        Map<String, Long> net = new LinkedHashMap<>();
        for (Leg leg : request.legs()) {
            if (leg.amount() <= 0) {
                throw LedgerException.invalid("amounts must be positive");
            }
            if (leg.accountId() == null && (leg.ownerId() == null || leg.ownerId().isBlank())) {
                throw LedgerException.invalid("each leg needs an accountId or an ownerId");
            }
            net.merge(leg.asset(), leg.direction().signed(leg.amount()), Math::addExact);
        }
        net.forEach((asset, sum) -> {
            if (sum != 0) {
                throw LedgerException.invalid("debits and credits differ for " + asset);
            }
        });
    }

    private static Map<String, Object> transferEvent(PostingResult result, List<ResolvedLeg> legs) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("transactionId", result.transactionId());
        event.put("idempotencyKey", result.idempotencyKey());
        for (ResolvedLeg leg : legs) {
            String side = leg.leg().direction() == Direction.DEBIT ? "fromAccountId" : "toAccountId";
            event.put(side, leg.accountId());
            event.put("asset", leg.leg().asset());
            event.put("amount", leg.leg().amount());
        }
        event.put("postedAt", result.createdAt().toString());
        return event;
    }

    private static void backoff(int attempt) {
        long cap = Math.min(1L << Math.min(attempt, 6), 50);
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(1, cap + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while retrying posting", e);
        }
    }

    static String sha256(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static StoredTransaction mapStored(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new StoredTransaction(rs.getString("request_hash"), new PostingResult(
                rs.getLong("id"), rs.getString("idempotency_key"),
                TransactionType.valueOf(rs.getString("type")), PostingStatus.valueOf(rs.getString("status")),
                rs.getString("reason"), rs.getString("reference"),
                rs.getTimestamp("created_at").toInstant(), false));
    }

    private record ResolvedLeg(long accountId, Leg leg) {
    }

    private record BalanceRow(long accountId, long balance, boolean allowNegative, long version) {
    }

    private record StoredTransaction(String requestHash, PostingResult result) {
    }

    static class OptimisticLockConflict extends RuntimeException {
        final long accountId;

        OptimisticLockConflict(long accountId) {
            super(null, null, false, false);
            this.accountId = accountId;
        }
    }
}
