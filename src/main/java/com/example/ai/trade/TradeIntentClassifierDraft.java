package com.example.ai.trade;

public record TradeIntentClassifierDraft(
        TradeIntentType intent,
        String itemId,
        int quantity,
        Integer counterTotalPrice,
        double confidence
) {
    public static TradeIntentClassifierDraft none() {
        return new TradeIntentClassifierDraft(TradeIntentType.NONE, "", 0, null, 0.0);
    }
}

