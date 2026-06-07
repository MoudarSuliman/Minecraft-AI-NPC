package com.example.ai.runtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class NpcBindingStore {
    private final Logger logger;
    private final Path basePath;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<UUID, BoundNpc> bindings = new LinkedHashMap<>();

    private NpcBindingStore(Logger logger, Path basePath) {
        this.logger = logger;
        this.basePath = basePath;
        load();
    }

    public static NpcBindingStore createDefault(Logger logger) {
        return new NpcBindingStore(logger, Path.of("config", "llm_npc", "bindings"));
    }

    public void rememberBinding(UUID npcId, String npcName, UUID ownerPlayerId) {
        bindings.put(npcId, new BoundNpc(npcName, ownerPlayerId == null ? null : ownerPlayerId.toString()));
        persist();
    }

    public Map<UUID, BoundNpc> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(bindings));
    }

    private void load() {
        try {
            Files.createDirectories(basePath);
            Path file = basePath.resolve("bindings.json");
            if (!Files.exists(file)) {
                return;
            }
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                UUID npcId;
                try {
                    npcId = UUID.fromString(entry.getKey());
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                if (!entry.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject json = entry.getValue().getAsJsonObject();
                String npcName = json.has("npc_name") ? json.get("npc_name").getAsString() : "";
                String ownerPlayerId = json.has("owner_player_id") && !json.get("owner_player_id").isJsonNull()
                        ? json.get("owner_player_id").getAsString()
                        : null;
                bindings.put(npcId, new BoundNpc(npcName, ownerPlayerId));
            }
        } catch (Exception e) {
            logger.warn("Failed to load NPC bindings", e);
        }
    }

    private void persist() {
        try {
            Files.createDirectories(basePath);
            JsonObject root = new JsonObject();
            for (Map.Entry<UUID, BoundNpc> entry : bindings.entrySet()) {
                JsonObject json = new JsonObject();
                json.addProperty("npc_name", entry.getValue().npcName());
                if (entry.getValue().ownerPlayerId() != null) {
                    json.addProperty("owner_player_id", entry.getValue().ownerPlayerId());
                } else {
                    json.add("owner_player_id", JsonNull.INSTANCE);
                }
                root.add(entry.getKey().toString(), json);
            }
            Files.writeString(basePath.resolve("bindings.json"), gson.toJson(root));
        } catch (IOException e) {
            logger.warn("Failed to persist NPC bindings", e);
        }
    }

    public record BoundNpc(String npcName, String ownerPlayerId) {
    }
}
