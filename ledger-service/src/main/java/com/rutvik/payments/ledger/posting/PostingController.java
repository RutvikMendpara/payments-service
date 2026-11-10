package com.rutvik.payments.ledger.posting;

import com.rutvik.payments.ledger.web.PostingResponses;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Internal API used by settlement: post an arbitrary balanced set of legs atomically. */
@RestController
public class PostingController {

    private final PostingService postings;

    public PostingController(PostingService postings) {
        this.postings = postings;
    }

    @PostMapping("/postings")
    public ResponseEntity<PostingResult> post(@RequestHeader("Idempotency-Key") String key,
                                              @Valid @RequestBody PostingRequest request) {
        return PostingResponses.of(postings.post(key, request));
    }

    @GetMapping("/transactions/{id}")
    public PostingResult byId(@PathVariable long id) {
        return postings.findResult(id).orElseThrow(() -> LedgerException.notFound("transaction " + id));
    }

    @GetMapping("/transactions")
    public PostingResult byKey(@RequestParam String idempotencyKey) {
        return postings.findResult(idempotencyKey)
                .orElseThrow(() -> LedgerException.notFound("transaction with that key"));
    }
}
