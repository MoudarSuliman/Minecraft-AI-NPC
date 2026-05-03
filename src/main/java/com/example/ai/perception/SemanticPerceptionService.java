package com.example.ai.perception;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SemanticPerceptionService {
    private static final int MAX_BLOCK_TYPES = 20;
    private static final int MAX_ENTITIES = 25;

    public WorldSnapshot capture(
            String npcName,
            WorldSnapshot.Position position,
            Map<String, Integer> nearbyBlocks,
            List<WorldSnapshot.SeenEntity> nearbyEntities,
            WorldSnapshot.EnvironmentContext environment
    ) {
        Map<String, Integer> compactBlocks = compressBlocks(nearbyBlocks);
        List<WorldSnapshot.SeenEntity> compactEntities = compressEntities(nearbyEntities);
        return new WorldSnapshot(npcName, position, compactBlocks, compactEntities, environment);
    }

    public WorldSnapshot captureFallback(String npcName) {
        return new WorldSnapshot(
                npcName,
                new WorldSnapshot.Position(0, 64, 0),
                Map.of("unknown", 1),
                List.of(),
                new WorldSnapshot.EnvironmentContext("unknown", 0L, false, "clear", "low")
        );
    }

    private Map<String, Integer> compressBlocks(Map<String, Integer> rawBlocks) {
        Map<String, Integer> sorted = new LinkedHashMap<>();
        rawBlocks.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(MAX_BLOCK_TYPES)
                .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return sorted;
    }

    private List<WorldSnapshot.SeenEntity> compressEntities(List<WorldSnapshot.SeenEntity> rawEntities) {
        return rawEntities.stream()
                .sorted((a, b) -> Double.compare(a.distance(), b.distance()))
                .limit(MAX_ENTITIES)
                .toList();
    }
}
