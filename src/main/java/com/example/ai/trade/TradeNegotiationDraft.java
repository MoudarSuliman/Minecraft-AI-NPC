package com.example.ai.trade;

public record TradeNegotiationDraft(
        String responseText,
        Integer suggestedUnitPrice,
        Integer suggestedTotalPrice,
        Integer counterTotalPrice,
        String reasoning,
        double priority
) {
}
