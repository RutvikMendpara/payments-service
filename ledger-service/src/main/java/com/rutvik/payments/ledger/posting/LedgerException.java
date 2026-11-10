package com.rutvik.payments.ledger.posting;

import org.springframework.http.HttpStatus;

public class LedgerException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public LedgerException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static LedgerException invalid(String message) {
        return new LedgerException(HttpStatus.BAD_REQUEST, "INVALID_POSTING", message);
    }

    public static LedgerException notFound(String what) {
        return new LedgerException(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " not found");
    }
}
