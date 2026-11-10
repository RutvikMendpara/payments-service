package com.rutvik.payments.settlement.fx;

import com.rutvik.payments.settlement.fx.FxTransferSaga.FxRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/fx-transfers")
public class FxController {

    private final FxTransferSaga saga;
    private final FxRepository repo;

    FxController(FxTransferSaga saga, FxRepository repo) {
        this.saga = saga;
        this.repo = repo;
    }

    public record Body(@NotBlank String fromOwner, @NotBlank String toOwner,
                       @Pattern(regexp = "^[A-Z]{3}$") String fromCurrency,
                       @Pattern(regexp = "^[A-Z]{3}$") String toCurrency,
                       @Positive long amount) {
    }

    /** amount is in minor units of the source currency. */
    @PostMapping
    public ResponseEntity<FxTransfer> create(@RequestHeader("Idempotency-Key") String key, @Valid @RequestBody Body b) {
        FxTransfer t = saga.start(key, new FxRequest(b.fromOwner(), b.toOwner(), b.fromCurrency(), b.toCurrency(), b.amount()));
        HttpStatus status = t.status() == FxStatus.COMPLETED ? HttpStatus.CREATED
                : t.status().terminal() ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(t);
    }

    @GetMapping("/{id}")
    public ResponseEntity<FxTransfer> get(@PathVariable UUID id) {
        return ResponseEntity.of(repo.find(id));
    }
}
