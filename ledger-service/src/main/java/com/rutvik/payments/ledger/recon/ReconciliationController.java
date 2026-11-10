package com.rutvik.payments.ledger.recon;

import com.rutvik.payments.ledger.posting.LedgerException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/reconciliation/runs")
public class ReconciliationController {

    private final ReconciliationService recon;

    public ReconciliationController(ReconciliationService recon) {
        this.recon = recon;
    }

    @PostMapping
    public ReconReport run() throws Exception {
        return recon.run();
    }

    @GetMapping("/latest")
    public ReconReport latest() {
        return recon.latest().orElseThrow(() -> LedgerException.notFound("reconciliation run"));
    }

    @GetMapping("/{id}")
    public ReconReport get(@PathVariable long id) {
        return recon.report(id).orElseThrow(() -> LedgerException.notFound("reconciliation run " + id));
    }
}
