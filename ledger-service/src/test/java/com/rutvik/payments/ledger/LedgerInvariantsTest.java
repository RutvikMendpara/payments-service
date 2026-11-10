package com.rutvik.payments.ledger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The database itself refuses to break the ledger rules, even if application code tried to. */
class LedgerInvariantsTest extends IntegrationTest {

    @Autowired
    TransactionTemplate tx;

    @Test
    void entriesCannotBeEditedOrDeleted() {
        long alice = openAccount("alice", "INR");
        fund(alice, "INR", 1_000);

        assertThatThrownBy(() -> jdbc.sql("update ledger_entries set amount = 1").update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("delete from ledger_entries").update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void unbalancedTransactionCannotCommit() {
        long alice = openAccount("alice", "INR");

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            long txId = jdbc.sql("""
                    insert into transactions (idempotency_key, request_hash, type, status)
                    values ('raw', repeat('0', 64), 'TRANSFER', 'POSTED') returning id""").query(Long.class).single();
            jdbc.sql("insert into ledger_entries (transaction_id, account_id, asset, direction, amount) "
                    + "values (:tx, :acc, 'INR', 'CREDIT', 500)").param("tx", txId).param("acc", alice).update();
        })).isInstanceOf(TransactionSystemException.class) // deferred trigger: fails at commit
                .rootCause().hasMessageContaining("not balanced");

        assertThat(count("select count(*) from transactions")).isZero();
    }

    @Test
    void customerBalanceCannotGoNegative() {
        long alice = openAccount("alice", "INR");
        assertThatThrownBy(() -> jdbc.sql("update balances set balance = -1 where account_id = :id")
                .param("id", alice).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("balance_not_negative");
    }

    @Test
    void systemAccountMayGoNegativeSoTotalsStayZero() {
        long alice = openAccount("alice", "INR");
        fund(alice, "INR", 1_000);
        long external = accounts.externalAccount("INR");
        assertThat(balance(external)).isEqualTo(-1_000);
        assertThat(count("select sum(balance) from balances")).isZero();
    }
}
