package com.rutvik.payments.ledger.recon;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/** Closes the recon_runs row and exposes the latest mismatch count as a metric to alert on. */
@Component
class ReconRunListener implements JobExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(ReconRunListener.class);

    private final JdbcClient jdbc;
    private final AtomicInteger lastMismatches = new AtomicInteger();

    ReconRunListener(JdbcClient jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        Gauge.builder("ledger.reconciliation.mismatches", lastMismatches, AtomicInteger::get).register(meters);
    }

    @Override
    public void afterJob(JobExecution execution) {
        long runId = execution.getJobParameters().getLong("runId");
        int mismatches = jdbc.sql("select count(*) from recon_mismatches where run_id = :id")
                .param("id", runId).query(Integer.class).single();
        String status = execution.getStatus() == BatchStatus.COMPLETED ? "COMPLETED" : "FAILED";
        jdbc.sql("update recon_runs set finished_at = now(), status = :status, mismatches = :n where id = :id")
                .param("status", status).param("n", mismatches).param("id", runId).update();
        lastMismatches.set(mismatches);
        if (mismatches > 0) {
            log.error("reconciliation run {} found {} mismatches", runId, mismatches);
        } else {
            log.info("reconciliation run {} {} with no mismatches", runId, status.toLowerCase());
        }
    }
}
