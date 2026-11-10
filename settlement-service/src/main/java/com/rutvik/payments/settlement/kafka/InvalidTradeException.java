package com.rutvik.payments.settlement.kafka;

/** The message itself is bad. Retrying won't fix it, so it goes straight to the DLT. */
public class InvalidTradeException extends RuntimeException {

    public InvalidTradeException(String message) {
        super(message);
    }

    public InvalidTradeException(String message, Throwable cause) {
        super(message, cause);
    }
}
