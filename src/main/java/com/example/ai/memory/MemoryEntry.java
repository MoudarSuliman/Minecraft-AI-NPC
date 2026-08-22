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
        return episodic(label, payload, true);
    }

    public static MemoryEntry episodic(String label, String payload, boolean success) {
        double base = switch (label) {
            case "trade"                  -> 0.85;
            case "scenario"               -> 0.80;
            case "search", "scout"        -> 0.75;
            case "task_result", "decision"-> 0.70;
            default                       -> 0.60;
        };
        double salience = success ? base : base * 0.65;
        return new MemoryEntry("episodic:" + label, System.currentTimeMillis(), payload, salience);
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("type", type);
        json.addProperty("age", relativeAge());
        json.addProperty("content", content);
        json.addProperty("salience", salience);
        return json;
    }

    public String relativeAge() {
        long deltaMillis = Math.max(0, System.currentTimeMillis() - timestamp);
        long minutes = deltaMillis / 60_000L;
        if (minutes < 1) {
            return "just now";
        }
        if (minutes < 60) {
            return minutes + " min ago";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + " h ago";
        }
        return (hours / 24) + " d ago";
    }
}
