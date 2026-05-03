package com.example.ai.intent;

import com.google.gson.JsonObject;

public record AgentDecision(
        AgentIntentType intent,
        JsonObject parameters,
        String reasoning,
        double priority
) {
    public boolean isValid() {
        return intent != null && parameters != null && !reasoning.isBlank() && priority >= 0.0 && priority <= 1.0;
    }
}
