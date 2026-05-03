package com.example.ai.memory;

import com.example.ai.intent.AgentDecision;
import com.google.gson.JsonObject;

public record MemoryEntry(
        String type,
        long timestamp,
        String content,
        double salience
) {
    public static MemoryEntry dialogue(String speaker, String text) {
        return new MemoryEntry("dialogue", System.currentTimeMillis(), speaker + ": " + text, 0.5);
    }

    public static MemoryEntry working(AgentDecision decision, boolean executed) {
        String content = "intent=" + decision.intent() + ", executed=" + executed + ", reason=" + decision.reasoning();
        return new MemoryEntry("working", System.currentTimeMillis(), content, 0.7);
    }

    public static MemoryEntry episodic(String label, String payload) {
        return new MemoryEntry("episodic:" + label, System.currentTimeMillis(), payload, 0.6);
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("type", type);
        json.addProperty("timestamp", timestamp);
        json.addProperty("content", content);
        json.addProperty("salience", salience);
        return json;
    }
}
