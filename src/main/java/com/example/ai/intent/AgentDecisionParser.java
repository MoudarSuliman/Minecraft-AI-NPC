package com.example.ai.intent;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Locale;

public final class AgentDecisionParser {
    private final Gson gson = new Gson();

    public AgentDecision parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return idle("empty response");
        }
        try {
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            String intentRaw = root.has("intent") ? root.get("intent").getAsString() : "idle";
            AgentIntentType intent = parseIntent(intentRaw);
            JsonObject params = root.has("parameters") && root.get("parameters").isJsonObject()
                    ? root.getAsJsonObject("parameters")
                    : new JsonObject();
            String reasoning = root.has("reasoning")
                    ? root.get("reasoning").getAsString()
                    : "";
            if (reasoning.isBlank()) {
                reasoning = "No reasoning provided";
            }
            double priority = root.has("priority") ? root.get("priority").getAsDouble() : 0.5;
            return new AgentDecision(intent, params, reasoning, clamp(priority));
        } catch (Exception ignored) {
            return idle("parser fallback");
        }
    }

    public String toJsonSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        properties.addProperty("intent", "string enum: idle|dialogue_reply|move_to|fetch_from_chest|mine_block|mine_to_chest|mine_to_player|place_block|break_block|build_structure");
        properties.addProperty("parameters", "object");
        properties.addProperty("reasoning", "string");
        properties.addProperty("priority", "number 0..1");
        schema.add("properties", properties);
        return gson.toJson(schema);
    }

    private AgentIntentType parseIntent(String raw) {
        String normalized = raw.toUpperCase(Locale.ROOT).trim();
        return switch (normalized) {
            case "DIALOGUE_REPLY" -> AgentIntentType.DIALOGUE_REPLY;
            case "MOVE_TO" -> AgentIntentType.MOVE_TO;
            case "FETCH_FROM_CHEST" -> AgentIntentType.FETCH_FROM_CHEST;
            case "MINE_BLOCK" -> AgentIntentType.MINE_BLOCK;
            case "MINE_TO_CHEST" -> AgentIntentType.MINE_TO_CHEST;
            case "MINE_TO_PLAYER" -> AgentIntentType.MINE_TO_PLAYER;
            case "PLACE_BLOCK" -> AgentIntentType.PLACE_BLOCK;
            case "BREAK_BLOCK" -> AgentIntentType.BREAK_BLOCK;
            case "BUILD_STRUCTURE" -> AgentIntentType.BUILD_STRUCTURE;
            default -> AgentIntentType.IDLE;
        };
    }

    private AgentDecision idle(String reason) {
        return new AgentDecision(AgentIntentType.IDLE, new JsonObject(), reason, 0.1);
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
