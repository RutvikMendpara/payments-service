package com.rutvik.payments.ledger.recon;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * Nightly check that the books are right, independently of the code that wrote them.
 * Each step streams rows from Postgres, the processor decides whether a row is a break,
 * and breaks are written to recon_mismatches for that run.
 *
 *  1. transactions: debits equal credits for every transaction and asset
 *  2. balances:     the balances table equals the sum of each account's entries
 *  3. totals:       every asset sums to zero across all accounts (system accounts included)
 */
@Configuration
public class ReconciliationJobConfig {

    record Totals(String reference, long debits, long credits, String detail) {
    }

    @Bean
    Job ledgerReconciliation(JobRepository jobs, Step transactionsStep, Step balancesStep, Step totalsStep,
                             ReconRunListener listener) {
        return new JobBuilder("ledgerReconciliation", jobs)
                .listener(listener)
                .start(transactionsStep)
                .next(balancesStep)
                .next(totalsStep)
                .build();
    }

    @Bean
    Step transactionsStep(JobRepository jobs, PlatformTransactionManager tm, DataSource ds,
                          JdbcBatchItemWriter<Mismatch> mismatchWriter,
                          @Value("${reconciliation.chunk-size}") int chunk) {
        JdbcCursorItemReader<Totals> reader = new JdbcCursorItemReaderBuilder<Totals>()
                .name("transactionTotals")
                .dataSource(ds)
                .sql("""
                        select transaction_id, asset,
                               sum(case when direction = 'DEBIT' then amount else 0 end) as debits,
                               sum(case when direction = 'CREDIT' then amount else 0 end) as credits
                        from ledger_entries
                        group by transaction_id, asset
                        order by transaction_id
                        """)
                .rowMapper((rs, i) -> new Totals(rs.getString("transaction_id"), rs.getLong("debits"),
                        rs.getLong("credits"), rs.getString("asset")))
                .build();
        return new StepBuilder("transactions", jobs)
                .<Totals, Mismatch>chunk(chunk, tm)
                .reader(reader)
                .processor(breakWhenDifferent("UNBALANCED_TRANSACTION"))
                .writer(mismatchWriter)
                .build();
    }

    @Bean
    Step balancesStep(JobRepository jobs, PlatformTransactionManager tm, DataSource ds,
                      JdbcBatchItemWriter<Mismatch> mismatchWriter,
                      @Value("${reconciliation.chunk-size}") int chunk) {
        JdbcCursorItemReader<Totals> reader = new JdbcCursorItemReaderBuilder<Totals>()
                .name("accountBalances")
                .dataSource(ds)
                .sql("""
                        select b.account_id, b.balance,
                               coalesce(sum(case when e.direction = 'CREDIT' then e.amount else -e.amount end), 0) as net,
                               a.owner_id || '/' || a.asset as detail
                        from balances b
                        join accounts a on a.id = b.account_id
                        left join ledger_entries e on e.account_id = b.account_id
                        group by b.account_id, b.balance, a.owner_id, a.asset
                        order by b.account_id
                        """)
                .rowMapper((rs, i) -> new Totals(rs.getString("account_id"), rs.getLong("net"),
                        rs.getLong("balance"), rs.getString("detail")))
                .build();
        return new StepBuilder("balances", jobs)
                .<Totals, Mismatch>chunk(chunk, tm)
                .reader(reader)
                .processor(breakWhenDifferent("BALANCE_DRIFT"))
                .writer(mismatchWriter)
                .build();
    }

    @Bean
    Step totalsStep(JobRepository jobs, PlatformTransactionManager tm, DataSource ds,
                    JdbcBatchItemWriter<Mismatch> mismatchWriter) {
        JdbcCursorItemReader<Totals> reader = new JdbcCursorItemReaderBuilder<Totals>()
                .name("assetTotals")
                .dataSource(ds)
                .sql("""
                        select a.asset, sum(b.balance) as total
                        from balances b join accounts a on a.id = b.account_id
                        group by a.asset order by a.asset
                        """)
                .rowMapper((rs, i) -> new Totals(rs.getString("asset"), 0, rs.getLong("total"),
                        "asset does not net to zero"))
                .build();
        return new StepBuilder("totals", jobs)
                .<Totals, Mismatch>chunk(100, tm)
                .reader(reader)
                .processor(breakWhenDifferent("ASSET_TOTAL"))
                .writer(mismatchWriter)
                .build();
    }

    /** Expected vs actual; equal rows are filtered out (returning null drops them from the chunk). */
    static ItemProcessor<Totals, Mismatch> breakWhenDifferent(String check) {
        return t -> t.debits() == t.credits() ? null
                : new Mismatch(check, t.reference(), t.debits(), t.credits(), t.detail());
    }

    @Bean
    @StepScope
    JdbcBatchItemWriter<Mismatch> mismatchWriter(DataSource ds, @Value("#{jobParameters['runId']}") Long runId) {
        return new JdbcBatchItemWriterBuilder<Mismatch>()
                .dataSource(ds)
                .sql("""
                        insert into recon_mismatches (run_id, check_name, reference, expected, actual, detail)
                        values (:runId, :checkName, :reference, :expected, :actual, :detail)
                        """)
                .itemSqlParameterSourceProvider(m -> new MapSqlParameterSource()
                        .addValue("runId", runId)
                        .addValue("checkName", m.checkName())
                        .addValue("reference", m.reference())
                        .addValue("expected", m.expected())
                        .addValue("actual", m.actual())
                        .addValue("detail", m.detail()))
                .build();
    }
}
