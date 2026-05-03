package com.example.ai.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AgentMemoryStore {
    private static final int SHORT_TERM_MAX = 12;
    private static final int WORKING_MAX = 16;
    private static final int LONG_TERM_MAX = 128;

    private final Logger logger;
    private final Path basePath;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<UUID, List<MemoryEntry>> shortTerm = new ConcurrentHashMap<>();
    private final Map<UUID, List<MemoryEntry>> working = new ConcurrentHashMap<>();
    private final Map<UUID, List<MemoryEntry>> longTerm = new ConcurrentHashMap<>();

    private AgentMemoryStore(Logger logger, Path basePath) {
        this.logger = logger;
        this.basePath = basePath;
    }

    public static AgentMemoryStore createDefault(Logger logger) {
        Path memoryPath = Path.of("config", "llm_npc", "memory");
        return new AgentMemoryStore(logger, memoryPath);
    }

    public void appendShortTerm(UUID npcId, MemoryEntry entry) {
        append(shortTerm, npcId, entry, SHORT_TERM_MAX);
    }

    public void appendWorking(UUID npcId, MemoryEntry entry) {
        append(working, npcId, entry, WORKING_MAX);
    }

    public void appendLongTerm(UUID npcId, MemoryEntry entry) {
        append(longTerm, npcId, entry, LONG_TERM_MAX);
        persist(npcId);
    }

    public MemoryContext getContext(UUID npcId) {
        List<MemoryEntry> shortList = new ArrayList<>(shortTerm.getOrDefault(npcId, List.of()));
        List<MemoryEntry> workingList = new ArrayList<>(working.getOrDefault(npcId, List.of()));
        List<MemoryEntry> longList = longTerm.getOrDefault(npcId, List.of()).stream()
                .sorted(Comparator.comparingDouble(MemoryEntry::salience).reversed())
                .limit(8)
                .toList();
        return new MemoryContext(shortList, workingList, longList);
    }

    private void append(Map<UUID, List<MemoryEntry>> target, UUID npcId, MemoryEntry entry, int maxSize) {
        target.compute(npcId, (id, list) -> {
            List<MemoryEntry> safe = list == null ? new ArrayList<>() : new ArrayList<>(list);
            safe.add(entry);
            if (safe.size() > maxSize) {
                safe.remove(0);
            }
            return safe;
        });
    }

    private void persist(UUID npcId) {
        try {
            Files.createDirectories(basePath);
            JsonObject root = new JsonObject();
            root.add("short_term", serializeList(shortTerm.getOrDefault(npcId, List.of())));
            root.add("working", serializeList(working.getOrDefault(npcId, List.of())));
            root.add("long_term", serializeList(longTerm.getOrDefault(npcId, List.of())));
            Files.writeString(basePath.resolve(npcId + ".json"), gson.toJson(root));
        } catch (IOException e) {
            logger.warn("Failed to persist memory for {}", npcId, e);
        }
    }

    private JsonArray serializeList(List<MemoryEntry> list) {
        JsonArray array = new JsonArray();
        for (MemoryEntry entry : list) {
            array.add(entry.toJson());
        }
        return array;
    }
}
