package com.rutvik.payments.ledger.account;

import com.rutvik.payments.ledger.posting.Direction;
import com.rutvik.payments.ledger.posting.TransactionType;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
public class AccountRepository {

    public static final String EXTERNAL_OWNER = "SYSTEM:EXTERNAL";

    private static final RowMapper<Account> ACCOUNT = (rs, i) -> new Account(
            rs.getLong("id"), rs.getString("owner_id"), rs.getString("asset"),
            AccountKind.valueOf(rs.getString("kind")), rs.getString("status"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns empty if the owner already has an account in that asset. */
    public Optional<Long> create(String ownerId, String asset, AccountKind kind) {
        Optional<Long> id = jdbc.sql("""
                        insert into accounts (owner_id, asset, kind) values (:owner, :asset, :kind)
                        on conflict (owner_id, asset) do nothing
                        returning id
                        """)
                .param("owner", ownerId).param("asset", asset).param("kind", kind.name())
                .query(Long.class).optional();
        id.ifPresent(accountId -> jdbc.sql("""
                        insert into balances (account_id, allow_negative) values (:id, :neg)
                        """)
                .param("id", accountId).param("neg", kind == AccountKind.SYSTEM)
                .update());
        return id;
    }

    public long findOrCreate(String ownerId, String asset, AccountKind kind) {
        return create(ownerId, asset, kind)
                .or(() -> findByOwnerAndAsset(ownerId, asset).map(Account::id))
                .orElseThrow();
    }

    public long externalAccount(String asset) {
        return findOrCreate(EXTERNAL_OWNER, asset, AccountKind.SYSTEM);
    }

    public Optional<Account> findById(long id) {
        return jdbc.sql("select * from accounts where id = :id").param("id", id).query(ACCOUNT).optional();
    }

    public Optional<Account> findByOwnerAndAsset(String ownerId, String asset) {
        return jdbc.sql("select * from accounts where owner_id = :owner and asset = :asset")
                .param("owner", ownerId).param("asset", asset)
                .query(ACCOUNT).optional();
    }

    public Optional<AccountView> view(long id) {
        return jdbc.sql("""
                        select a.*, b.balance, b.version from accounts a
                        join balances b on b.account_id = a.id
                        where a.id = :id
                        """)
                .param("id", id).query(AccountRepository::toView).optional();
    }

    public List<AccountView> list(String ownerId, int limit, int offset) {
        return jdbc.sql("""
                        select a.*, b.balance, b.version from accounts a
                        join balances b on b.account_id = a.id
                        where (cast(:owner as varchar) is null or a.owner_id = :owner)
                        order by a.id
                        limit :limit offset :offset
                        """)
                .param("owner", ownerId).param("limit", limit).param("offset", offset)
                .query(AccountRepository::toView).list();
    }

    public List<EntryView> entries(long accountId, int limit, int offset) {
        return jdbc.sql("""
                        select e.id, e.transaction_id, t.type, t.reference, e.direction, e.amount, e.created_at
                        from ledger_entries e
                        join transactions t on t.id = e.transaction_id
                        where e.account_id = :id
                        order by e.id desc
                        limit :limit offset :offset
                        """)
                .param("id", accountId).param("limit", limit).param("offset", offset)
                .query((rs, i) -> new EntryView(
                        rs.getLong("id"), rs.getLong("transaction_id"),
                        TransactionType.valueOf(rs.getString("type")), rs.getString("reference"),
                        Direction.valueOf(rs.getString("direction")), rs.getLong("amount"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    private static AccountView toView(ResultSet rs, int row) throws SQLException {
        return new AccountView(rs.getLong("id"), rs.getString("owner_id"), rs.getString("asset"),
                AccountKind.valueOf(rs.getString("kind")), rs.getString("status"),
                rs.getLong("balance"), rs.getLong("version"), rs.getTimestamp("created_at").toInstant());
    }
}
