package com.example.ai.trade;

public record TradeOffer(
        String itemId,
        int quantity,
        int unitPrice,
        int totalPrice,
        int maxDiscountPct,
        long expiresAtMillis
) {
}
