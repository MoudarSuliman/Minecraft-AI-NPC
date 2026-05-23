package com.example.ai.trade;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class TradeIntentClassifierParser {
    public TradeIntentClassifierDraft parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return TradeIntentClassifierDraft.none();
        }
        try {
            JsonObject root = parseObject(raw);
            if (root == null) {
                return TradeIntentClassifierDraft.none();
            }
            JsonObject payload = extractIntentPayload(root);
            String intentRaw = payload.has("intent") ? payload.get("intent").getAsString() : "none";
            TradeIntentType intent = switch (normalizeIntent(intentRaw)) {
                case "inquire_stock" -> TradeIntentType.INQUIRE_STOCK;
                case "inquire_payment" -> TradeIntentType.INQUIRE_PAYMENT;
                case "inquire_session_status" -> TradeIntentType.INQUIRE_SESSION_STATUS;
                case "request_offer" -> TradeIntentType.REQUEST_OFFER;
                case "accept_offer" -> TradeIntentType.ACCEPT_OFFER;
                case "decline_offer" -> TradeIntentType.DECLINE_OFFER;
                case "counter_offer" -> TradeIntentType.COUNTER_OFFER;
                default -> TradeIntentType.NONE;
            };
            String itemId = payload.has("item_id") ? payload.get("item_id").getAsString() : "";
            itemId = normalizeItemId(itemId);
            int quantity = intValue(payload.get("quantity"));
            quantity = Math.max(0, quantity);
            Integer counterTotal = nullableIntValue(payload.get("counter_total_price"));
            double confidence = 0.0;
            if (payload.has("confidence")) {
                confidence = doubleValue(payload.get("confidence"));
            }
            confidence = Math.max(0.0, Math.min(1.0, confidence));
            return new TradeIntentClassifierDraft(intent, itemId == null ? "" : itemId, quantity, counterTotal, confidence);
        } catch (Exception ignored) {
            return TradeIntentClassifierDraft.none();
        }
    }

    private JsonObject parseObject(String raw) {
        try {
            return JsonParser.parseString(raw).getAsJsonObject();
        } catch (Exception ignored) {
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return JsonParser.parseString(raw.substring(start, end + 1)).getAsJsonObject();
        } catch (Exception ignored) {
            return null;
        }
    }

    private JsonObject extractIntentPayload(JsonObject root) {
        if (hasIntentField(root)) {
            return root;
        }
        if (isPromptEnvelope(root)) {
            JsonObject wrapped = extractKnownWrapper(root);
            return wrapped == null ? root : wrapped;
        }
        JsonObject wrapped = extractKnownWrapper(root);
        if (wrapped != null) {
            return wrapped;
        }
        JsonObject nested = findIntentObject(root, 0);
        return nested == null ? root : nested;
    }

    private JsonObject extractKnownWrapper(JsonObject root) {
        String[] wrapperKeys = {"output", "result", "response", "classification"};
        for (String key : wrapperKeys) {
            if (!root.has(key) || !root.get(key).isJsonObject()) {
                continue;
            }
            JsonObject candidate = root.getAsJsonObject(key);
            if (hasIntentField(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isPromptEnvelope(JsonObject root) {
        if (!root.has("task") || !root.get("task").isJsonPrimitive()) {
            return false;
        }
        String task = root.get("task").getAsString().toLowerCase();
        return task.contains("trade_intent");
    }

    private JsonObject findIntentObject(JsonObject node, int depth) {
        if (depth > 4) {
            return null;
        }
        for (var entry : node.entrySet()) {
            String key = entry.getKey();
            if (isIgnoredPromptField(key)) {
                continue;
            }
            JsonElement value = entry.getValue();
            if (value == null) {
                continue;
            }
            if (value.isJsonObject()) {
                JsonObject object = value.getAsJsonObject();
                if (hasIntentField(object)) {
                    return object;
                }
                JsonObject nested = findIntentObject(object, depth + 1);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private boolean isIgnoredPromptField(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase();
        return normalized.equals("format")
                || normalized.equals("constraints")
                || normalized.equals("initial_classification")
                || normalized.equals("perception")
                || normalized.equals("memory")
                || normalized.equals("stock_summary")
                || normalized.equals("active_offer")
                || normalized.equals("player_utterance")
                || normalized.equals("last_requested_item_id")
                || normalized.equals("task");
    }

    private boolean hasIntentField(JsonObject object) {
        return object.has("intent") && object.get("intent").isJsonPrimitive();
    }

    private int intValue(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return 0;
        }
        try {
            return (int) Math.round(element.getAsDouble());
        } catch (Exception ignored) {
            return 0;
        }
    }

    private Integer nullableIntValue(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        try {
            return (int) Math.round(element.getAsDouble());
        } catch (Exception ignored) {
            return null;
        }
    }

    private double doubleValue(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return 0.0;
        }
        try {
            return element.getAsDouble();
        } catch (Exception ignored) {
            return 0.0;
        }
    }

    private String normalizeIntent(String intentRaw) {
        if (intentRaw == null) {
            return "none";
        }
        String normalized = intentRaw.trim().toLowerCase().replace('-', '_').replace(' ', '_');
        return switch (normalized) {
            case "stock", "stock_inquiry", "stock_query" -> "inquire_stock";
            case "payment", "payment_inquiry" -> "inquire_payment";
            case "session_status", "status" -> "inquire_session_status";
            case "offer", "request", "quote" -> "request_offer";
            case "accept", "accepted" -> "accept_offer";
            case "decline", "reject", "rejected" -> "decline_offer";
            case "counter", "counteroffer", "counter_offer" -> "counter_offer";
            default -> normalized;
        };
    }

    private String normalizeItemId(String rawItemId) {
        if (rawItemId == null) {
            return "";
        }
        String normalized = rawItemId.trim().toLowerCase();
        if (normalized.isBlank()) {
            return "";
        }
        if (normalized.startsWith("minecraft:")) {
            return normalized;
        }
        return switch (normalized) {
            case "stick", "sticks" -> "minecraft:stick";
            case "grass", "grass_block", "grass block" -> "minecraft:grass_block";
            case "glass", "glass_block", "glass block" -> "minecraft:glass";
            case "cobblestone" -> "minecraft:cobblestone";
            case "oak_log", "oak log", "log", "logs" -> "minecraft:oak_log";
            case "oak_planks", "oak plank", "oak planks", "plank", "planks" -> "minecraft:oak_planks";
            case "stone" -> "minecraft:stone";
            case "dirt" -> "minecraft:dirt";
            case "sand" -> "minecraft:sand";
            default -> normalized.contains(":") ? normalized : "minecraft:" + normalized.replace(' ', '_');
        };
    }
}

