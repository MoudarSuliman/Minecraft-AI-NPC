package com.example.ai.perception;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

public record WorldSnapshot(
        String npcName,
        Position npcPosition,
        Map<String, Integer> nearbyBlockHistogram,
        List<SeenEntity> nearbyEntities,
        EnvironmentContext environment
) {
    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("npc_name", npcName);
        root.add("npc_position", npcPosition.toJson());

        JsonObject blocks = new JsonObject();
        nearbyBlockHistogram.forEach(blocks::addProperty);
        root.add("nearby_blocks", blocks);

        JsonArray entities = new JsonArray();
        for (SeenEntity entity : nearbyEntities) {
            entities.add(entity.toJson());
        }
        root.add("nearby_entities", entities);
        root.add("environment", environment.toJson());
        return root;
    }

    public record Position(int x, int y, int z) {
        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("x", x);
            json.addProperty("y", y);
            json.addProperty("z", z);
            return json;
        }
    }

    public record SeenEntity(String type, String name, double distance) {
        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("type", type);
            json.addProperty("name", name);
            json.addProperty("distance", distance);
            return json;
        }
    }

    public record EnvironmentContext(
            String biome,
            long timeOfDay,
            boolean isNight,
            String weather,
            String threatLevel
    ) {
        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("biome", biome);
            json.addProperty("time_of_day", timeOfDay);
            json.addProperty("is_night", isNight);
            json.addProperty("weather", weather);
            json.addProperty("threat_level", threatLevel);
            return json;
        }
    }
}
