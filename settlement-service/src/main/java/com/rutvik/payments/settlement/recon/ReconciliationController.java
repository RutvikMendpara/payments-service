package com.rutvik.payments.settlement.recon;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
    public ResponseEntity<ReconReport> latest() {
        return ResponseEntity.of(recon.latest());
    }
}
