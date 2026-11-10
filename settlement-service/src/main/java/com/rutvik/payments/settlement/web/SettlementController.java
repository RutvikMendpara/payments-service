package com.rutvik.payments.settlement.web;

import com.rutvik.payments.settlement.Settlement;
import com.rutvik.payments.settlement.SettlementRepository;
import com.rutvik.payments.settlement.SettlementService;
import com.rutvik.payments.settlement.SettlementStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/settlements")
@Validated
public class SettlementController {

    private final SettlementRepository settlements;
    private final SettlementService service;

    public SettlementController(SettlementRepository settlements, SettlementService service) {
        this.settlements = settlements;
        this.service = service;
    }

    @GetMapping("/{tradeId}")
    public ResponseEntity<?> get(@PathVariable String tradeId) {
        return settlements.find(tradeId).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> notFound(tradeId));
    }

    @GetMapping
    public List<Settlement> list(@RequestParam(required = false) SettlementStatus status,
                                 @RequestParam(defaultValue = "0") @Min(0) int page,
                                 @RequestParam(defaultValue = "50") @Min(1) @Max(500) int size) {
        return settlements.list(status, size, page * size);
    }

    @GetMapping("/stats")
    public Map<String, Long> stats() {
        return settlements.countByStatus();
    }

    /** Re-run a FAILED trade with a fresh ledger key. Anything not FAILED is returned unchanged. */
    @PostMapping("/{tradeId}/retry")
    public ResponseEntity<?> retry(@PathVariable String tradeId) {
        return service.retry(tradeId).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> notFound(tradeId));
    }

    private static ResponseEntity<ProblemDetail> notFound(String tradeId) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "no settlement for trade " + tradeId));
    }
}
