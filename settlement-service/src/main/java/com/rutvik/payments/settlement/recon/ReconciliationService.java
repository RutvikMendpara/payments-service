package com.rutvik.payments.settlement.recon;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class ReconciliationService {

    private final JobLauncher launcher;
    private final Job job;
    private final JdbcClient jdbc;

    public ReconciliationService(JobLauncher launcher, Job settlementReconciliation, JdbcClient jdbc) {
        this.launcher = launcher;
        this.job = settlementReconciliation;
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "${reconciliation.cron}")
    public void nightly() throws Exception {
        run();
    }

    public synchronized ReconReport run() throws Exception {
        long runId = jdbc.sql("insert into recon_runs (status) values ('RUNNING') returning id").query(Long.class).single();
        launcher.run(job, new JobParametersBuilder()
                .addLong("runId", runId)
                .addLong("startedAt", System.currentTimeMillis())
                .toJobParameters());
        return report(runId).orElseThrow();
    }

    public Optional<ReconReport> latest() {
        return jdbc.sql("select max(id) from recon_runs").query(Long.class).optional().flatMap(this::report);
    }

    private Optional<ReconReport> report(Long runId) {
        if (runId == null) {
            return Optional.empty();
        }
        var mismatches = jdbc.sql("select trade_id, check_name, detail from recon_mismatches where run_id = :id order by id limit 1000")
                .param("id", runId)
                .query((rs, i) -> new Mismatch(rs.getString(1), rs.getString(2), rs.getString(3))).list();
        return jdbc.sql("select * from recon_runs where id = :id").param("id", runId)
                .query((rs, i) -> new ReconReport(rs.getLong("id"), rs.getString("status"), rs.getInt("checked"),
                        rs.getInt("mismatches"), mismatches))
                .optional();
    }
}
