package com.example.ai.trade;

public record TradeCounterResult(
        boolean accepted,
        int minimumAcceptableTotal,
        TradeOffer offer
) {
}
