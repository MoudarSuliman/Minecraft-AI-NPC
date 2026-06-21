package com.example.ai.scenario;

import java.util.List;

public record ScenarioPlan(
        String scenario,
        String announce,
        List<ScenarioStep> steps
) {
    public boolean isValid() {
        if (steps == null || steps.isEmpty()) return false;
        return steps.stream().anyMatch(s -> {
            String intent = s.intent();
            return "fetch_from_chest".equals(intent) || "place_block".equals(intent)
                    || "place_pattern".equals(intent) || "mine_block".equals(intent)
                    || "mine_to_player".equals(intent) || "mine_to_chest".equals(intent);
        });
    }
}
