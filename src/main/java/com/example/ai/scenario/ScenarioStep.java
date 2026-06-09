package com.example.ai.scenario;

import com.google.gson.JsonObject;

public record ScenarioStep(
        String intent,
        JsonObject parameters,
        String description
) {}
