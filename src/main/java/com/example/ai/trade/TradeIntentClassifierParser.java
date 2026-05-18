package com.example.ai.trade;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class TradeIntentClassifierParser {
    public TradeIntentClassifierDraft parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return TradeIntentClassifierDraft.none();
        }
        try {
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            String intentRaw = root.has("intent") ? root.get("intent").getAsString() : "none";
            TradeIntentType intent = switch (intentRaw.toLowerCase()) {
                case "inquire_stock" -> TradeIntentType.INQUIRE_STOCK;
                case "inquire_payment" -> TradeIntentType.INQUIRE_PAYMENT;
                case "inquire_session_status" -> TradeIntentType.INQUIRE_SESSION_STATUS;
                case "request_offer" -> TradeIntentType.REQUEST_OFFER;
                case "accept_offer" -> TradeIntentType.ACCEPT_OFFER;
                case "decline_offer" -> TradeIntentType.DECLINE_OFFER;
                case "counter_offer" -> TradeIntentType.COUNTER_OFFER;
                default -> TradeIntentType.NONE;
            };
            String itemId = root.has("item_id") ? root.get("item_id").getAsString() : "";
            int quantity = root.has("quantity") && root.get("quantity").isJsonPrimitive()
                    && root.get("quantity").getAsJsonPrimitive().isNumber()
                    ? Math.max(0, root.get("quantity").getAsInt())
                    : 0;
            Integer counterTotal = root.has("counter_total_price") && root.get("counter_total_price").isJsonPrimitive()
                    && root.get("counter_total_price").getAsJsonPrimitive().isNumber()
                    ? root.get("counter_total_price").getAsInt()
                    : null;
            double confidence = root.has("confidence") && root.get("confidence").isJsonPrimitive()
                    && root.get("confidence").getAsJsonPrimitive().isNumber()
                    ? root.get("confidence").getAsDouble()
                    : 0.0;
            confidence = Math.max(0.0, Math.min(1.0, confidence));
            return new TradeIntentClassifierDraft(intent, itemId == null ? "" : itemId, quantity, counterTotal, confidence);
        } catch (Exception ignored) {
            return TradeIntentClassifierDraft.none();
        }
    }
}

