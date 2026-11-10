package com.rutvik.payments.ledger.web;

import com.rutvik.payments.ledger.posting.PostingResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

public final class PostingResponses {

    private PostingResponses() {
    }

    public static ResponseEntity<PostingResult> of(PostingResult result) {
        HttpStatus status = result.posted() ? HttpStatus.CREATED : HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(status)
                .header("Idempotent-Replayed", Boolean.toString(result.replayed()))
                .body(result);
    }
}
