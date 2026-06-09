package com.example.ai.scenario;

import java.util.List;

public record ScenarioPlan(
        String scenario,
        String announce,
        List<ScenarioStep> steps
) {
    public boolean isValid() {
        return steps != null && !steps.isEmpty();
    }
}
