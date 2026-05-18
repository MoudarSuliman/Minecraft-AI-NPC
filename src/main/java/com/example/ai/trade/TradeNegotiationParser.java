package com.example.ai.trade;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class TradeNegotiationParser {
    public TradeNegotiationDraft parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new TradeNegotiationDraft("", null, null, null, "empty response", 0.1);
        }
        try {
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            String responseText = root.has("response_text") ? root.get("response_text").getAsString() : "";
            Integer suggestedUnitPrice = root.has("suggested_unit_price") && root.get("suggested_unit_price").isJsonPrimitive()
                    && root.get("suggested_unit_price").getAsJsonPrimitive().isNumber()
                    ? root.get("suggested_unit_price").getAsInt()
                    : null;
            Integer suggestedTotalPrice = root.has("suggested_total_price") && root.get("suggested_total_price").isJsonPrimitive()
                    && root.get("suggested_total_price").getAsJsonPrimitive().isNumber()
                    ? root.get("suggested_total_price").getAsInt()
                    : null;
            Integer counterTotalPrice = root.has("counter_total_price") && root.get("counter_total_price").isJsonPrimitive()
                    && root.get("counter_total_price").getAsJsonPrimitive().isNumber()
                    ? root.get("counter_total_price").getAsInt()
                    : null;
            String reasoning = root.has("reasoning") ? root.get("reasoning").getAsString() : "";
            if (reasoning.isBlank()) {
                reasoning = "No reasoning provided";
            }
            double priority = root.has("priority") ? root.get("priority").getAsDouble() : 0.5;
            return new TradeNegotiationDraft(
                    responseText,
                    suggestedUnitPrice,
                    suggestedTotalPrice,
                    counterTotalPrice,
                    reasoning,
                    Math.max(0.0, Math.min(1.0, priority))
            );
        } catch (Exception ignored) {
            String fallbackText = raw
                    .replace("```json", "")
                    .replace("```", "")
                    .trim();
            return new TradeNegotiationDraft(fallbackText, null, null, null, "parser fallback", 0.3);
        }
    }
}
