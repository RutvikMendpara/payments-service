package com.rutvik.payments.settlement.recon;

import com.rutvik.payments.settlement.Settlement;
import com.rutvik.payments.settlement.SettlementRepository;
import com.rutvik.payments.settlement.ledger.LedgerClient;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
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
import java.time.Duration;

/** Checks every settlement touched in the last two days against the ledger. */
@Configuration
public class ReconciliationJobConfig {

    @Bean
    Job settlementReconciliation(JobRepository jobs, Step settlementVsLedgerStep, ReconRunListener listener) {
        return new JobBuilder("settlementReconciliation", jobs)
                .listener(listener)
                .start(settlementVsLedgerStep)
                .build();
    }

    @Bean
    Step settlementVsLedgerStep(JobRepository jobs, PlatformTransactionManager tm, DataSource ds, LedgerClient ledger,
                                JdbcBatchItemWriter<Mismatch> mismatchWriter,
                                @Value("${reconciliation.stuck-after}") Duration stuckAfter) {
        JdbcCursorItemReader<Settlement> reader = new JdbcCursorItemReaderBuilder<Settlement>()
                .name("recentSettlements")
                .dataSource(ds)
                .sql("select * from settlements where updated_at > now() - interval '2 days' order by trade_id")
                .rowMapper(SettlementRepository.ROW)
                .build();
        return new StepBuilder("settlementVsLedger", jobs)
                .<Settlement, Mismatch>chunk(200, tm)
                .reader(reader)
                .processor(new SettlementVsLedger(ledger, stuckAfter))
                .writer(mismatchWriter)
                .build();
    }

    @Bean
    @StepScope
    JdbcBatchItemWriter<Mismatch> mismatchWriter(DataSource ds, @Value("#{jobParameters['runId']}") Long runId) {
        return new JdbcBatchItemWriterBuilder<Mismatch>()
                .dataSource(ds)
                .sql("insert into recon_mismatches (run_id, trade_id, check_name, detail) "
                        + "values (:runId, :tradeId, :checkName, :detail)")
                .itemSqlParameterSourceProvider(m -> new MapSqlParameterSource()
                        .addValue("runId", runId)
                        .addValue("tradeId", m.tradeId())
                        .addValue("checkName", m.checkName())
                        .addValue("detail", m.detail()))
                .build();
    }
}
