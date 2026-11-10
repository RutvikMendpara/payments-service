package com.rutvik.payments.settlement.recon;

public record Mismatch(String tradeId, String checkName, String detail) {
}
