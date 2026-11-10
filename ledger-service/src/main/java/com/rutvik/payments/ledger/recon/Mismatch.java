package com.rutvik.payments.ledger.recon;

public record Mismatch(String checkName, String reference, long expected, long actual, String detail) {
}
