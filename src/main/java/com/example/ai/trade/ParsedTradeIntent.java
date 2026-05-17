package com.example.ai.trade;

public record ParsedTradeIntent(
        TradeIntentType type,
        String itemId,
        int quantity,
        Integer counterTotalPrice
) {
    public static ParsedTradeIntent none() {
        return new ParsedTradeIntent(TradeIntentType.NONE, "", 0, null);
    }
}
