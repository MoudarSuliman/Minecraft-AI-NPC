package com.example.ai.scenario;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

public final class ScenarioPlanParser {

    public ScenarioPlan parse(String raw) {
        if (raw == null || raw.isBlank()) return fallback();
        try {
            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}');
            if (start < 0 || end <= start) return fallback();
            JsonObject root = JsonParser.parseString(raw.substring(start, end + 1)).getAsJsonObject();

            String scenario = root.has("scenario") ? root.get("scenario").getAsString() : "task";
            String announce = root.has("announce") ? root.get("announce").getAsString() : "";

            List<ScenarioStep> steps = new ArrayList<>();
            if (root.has("steps") && root.get("steps").isJsonArray()) {
                JsonArray arr = root.getAsJsonArray("steps");
                for (JsonElement el : arr) {
                    if (!el.isJsonObject()) continue;
                    JsonObject obj = el.getAsJsonObject();
                    String intent = obj.has("intent")
                            ? obj.get("intent").getAsString().toLowerCase(java.util.Locale.ROOT)
                            : "dialogue_reply";
                    JsonObject params = obj.has("parameters") && obj.get("parameters").isJsonObject()
                            ? obj.getAsJsonObject("parameters")
                            : new JsonObject();
                    String desc = obj.has("description") ? obj.get("description").getAsString() : intent;
                    steps.add(new ScenarioStep(intent, params, desc));
                }
            }
            if (steps.isEmpty()) return fallback();
            return new ScenarioPlan(scenario, announce, steps);
        } catch (Exception ignored) {
            return fallback();
        }
    }

    private ScenarioPlan fallback() {
        JsonObject p = new JsonObject();
        p.addProperty("text", "I wasn't able to plan that task. Could you be more specific?");
        return new ScenarioPlan("failed", "", List.of(new ScenarioStep("dialogue_reply", p, "report failure")));
    }
}
