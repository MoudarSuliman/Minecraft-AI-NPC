package com.example.ai.memory;

import org.slf4j.Logger;

import java.util.Arrays;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class AgentMemoryStore {
    private static final int SHORT_TERM_MAX = 12;
    private static final int WORKING_MAX = 16;
    private static final int LONG_TERM_MAX = 128;

    private final Logger logger;
    private final Path dbPath;
    private Connection connection;

    private final Map<UUID, List<MemoryEntry>> shortTerm = new ConcurrentHashMap<>();
    private final Map<UUID, List<MemoryEntry>> working = new ConcurrentHashMap<>();
    private final Map<String, List<MemoryEntry>> longTermCache = new ConcurrentHashMap<>();
    private final Map<String, Boolean> loaded = new ConcurrentHashMap<>();

    private AgentMemoryStore(Logger logger, Path dbPath) {
        this.logger = logger;
        this.dbPath = dbPath;
    }

    public static AgentMemoryStore createDefault(Logger logger) {
        Path dbPath = Path.of("config", "llm_npc", "memory.db");
        AgentMemoryStore store = new AgentMemoryStore(logger, dbPath);
        store.initDatabase();
        return store;
    }

    private void initDatabase() {
        try {
            Files.createDirectories(dbPath.getParent());
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
            try (var stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
                stmt.execute("""
                        CREATE TABLE IF NOT EXISTS long_term_memory (
                            id          INTEGER PRIMARY KEY AUTOINCREMENT,
                            npc_uuid    TEXT    NOT NULL,
                            player_uuid TEXT,
                            type        TEXT    NOT NULL,
                            timestamp   INTEGER NOT NULL,
                            content     TEXT    NOT NULL,
                            salience    REAL    NOT NULL
                        )""");
                // migrate existing databases that lack the player_uuid column
                try {
                    stmt.execute("ALTER TABLE long_term_memory ADD COLUMN player_uuid TEXT");
                } catch (Exception ignored) {
                    // column already exists
                }
                stmt.execute(
                        "CREATE INDEX IF NOT EXISTS idx_npc ON long_term_memory(npc_uuid)");
                stmt.execute(
                        "CREATE INDEX IF NOT EXISTS idx_npc_player ON long_term_memory(npc_uuid, player_uuid)");
            }
        } catch (Exception e) {
            logger.error("Failed to initialise SQLite memory database at {}", dbPath, e);
        }
    }

    // --- short-term and working memory (NPC-scoped, not per-player) ---

    public void appendShortTerm(UUID npcId, MemoryEntry entry) {
        appendRam(shortTerm, npcId, entry, SHORT_TERM_MAX);
    }

    public void appendWorking(UUID npcId, MemoryEntry entry) {
        appendRam(working, npcId, entry, WORKING_MAX);
    }

    // --- long-term memory (per NPC + per player) ---

    public void appendLongTerm(UUID npcId, UUID playerId, MemoryEntry entry) {
        String key = cacheKey(npcId, playerId);
        ensureLoaded(npcId, playerId);
        appendRamByKey(longTermCache, key, entry, LONG_TERM_MAX);
        persistLongTerm(npcId, playerId, entry);
    }

    /** Backwards-compatible overload — stores without player association. */
    public void appendLongTerm(UUID npcId, MemoryEntry entry) {
        appendLongTerm(npcId, null, entry);
    }

    // --- context retrieval ---

    public MemoryContext getContext(UUID npcId, UUID playerId) {
        return getContext(npcId, playerId, null);
    }

    public MemoryContext getContext(UUID npcId, UUID playerId, String query) {
        ensureLoaded(npcId, playerId);
        String key = cacheKey(npcId, playerId);
        List<MemoryEntry> shortList   = new ArrayList<>(shortTerm.getOrDefault(npcId, List.of()));
        List<MemoryEntry> workingList = new ArrayList<>(working.getOrDefault(npcId, List.of()));
        long now = System.currentTimeMillis();
        List<MemoryEntry> longList    = longTermCache.getOrDefault(key, List.of()).stream()
                .sorted(Comparator.comparingDouble((MemoryEntry e) -> effectiveSalience(e, now, query)).reversed())
                .limit(8)
                .toList();
        return new MemoryContext(shortList, workingList, longList);
    }

    /** Backwards-compatible overloads. */
    public MemoryContext getContext(UUID npcId) {
        return getContext(npcId, (UUID) null, null);
    }

    public MemoryContext getContext(UUID npcId, String query) {
        return getContext(npcId, (UUID) null, query);
    }

    // --- internals ---

    private static String cacheKey(UUID npcId, UUID playerId) {
        return npcId + ":" + (playerId != null ? playerId : "shared");
    }

    private void appendRam(Map<UUID, List<MemoryEntry>> target, UUID npcId,
                           MemoryEntry entry, int maxSize) {
        target.compute(npcId, (id, list) -> {
            List<MemoryEntry> safe = list == null ? new ArrayList<>() : new ArrayList<>(list);
            safe.add(entry);
            if (safe.size() > maxSize) safe.remove(0);
            return safe;
        });
    }

    private void appendRamByKey(Map<String, List<MemoryEntry>> target, String key,
                                MemoryEntry entry, int maxSize) {
        target.compute(key, (k, list) -> {
            List<MemoryEntry> safe = list == null ? new ArrayList<>() : new ArrayList<>(list);
            safe.add(entry);
            if (safe.size() > maxSize) safe.remove(0);
            return safe;
        });
    }

    private void persistLongTerm(UUID npcId, UUID playerId, MemoryEntry entry) {
        if (connection == null) return;
        String playerStr = playerId != null ? playerId.toString() : null;
        try {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO long_term_memory (npc_uuid, player_uuid, type, timestamp, content, salience) VALUES (?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, npcId.toString());
                ps.setString(2, playerStr);
                ps.setString(3, entry.type());
                ps.setLong(4,   entry.timestamp());
                ps.setString(5, entry.content());
                ps.setDouble(6, entry.salience());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement("""
                    DELETE FROM long_term_memory
                    WHERE npc_uuid = ?
                      AND (player_uuid = ? OR (player_uuid IS NULL AND ? IS NULL))
                      AND id NOT IN (
                          SELECT id FROM long_term_memory
                          WHERE npc_uuid = ?
                            AND (player_uuid = ? OR (player_uuid IS NULL AND ? IS NULL))
                          ORDER BY timestamp DESC
                          LIMIT ?
                      )""")) {
                ps.setString(1, npcId.toString());
                ps.setString(2, playerStr);
                ps.setString(3, playerStr);
                ps.setString(4, npcId.toString());
                ps.setString(5, playerStr);
                ps.setString(6, playerStr);
                ps.setInt(7,    LONG_TERM_MAX);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.warn("Failed to persist long-term memory for npc={} player={}", npcId, playerId, e);
        }
    }

    private void ensureLoaded(UUID npcId, UUID playerId) {
        String key = cacheKey(npcId, playerId);
        if (loaded.putIfAbsent(key, true) != null) return;
        if (connection == null) return;
        String playerStr = playerId != null ? playerId.toString() : null;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT type, timestamp, content, salience FROM long_term_memory " +
                "WHERE npc_uuid = ? AND (player_uuid = ? OR (player_uuid IS NULL AND ? IS NULL)) " +
                "ORDER BY timestamp ASC")) {
            ps.setString(1, npcId.toString());
            ps.setString(2, playerStr);
            ps.setString(3, playerStr);
            List<MemoryEntry> entries = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(new MemoryEntry(
                            rs.getString("type"),
                            rs.getLong("timestamp"),
                            rs.getString("content"),
                            rs.getDouble("salience")));
                }
            }
            longTermCache.put(key, entries);
        } catch (SQLException e) {
            logger.warn("Failed to load long-term memory for npc={} player={}", npcId, playerId, e);
        }
    }

    private static double effectiveSalience(MemoryEntry e, long nowMs, String query) {
        double ageHours = (nowMs - e.timestamp()) / 3_600_000.0;
        double recency   = 1.0 / (1.0 + ageHours / 168.0);
        double relevance = relevanceScore(e, query);
        return e.salience() * recency * relevance;
    }

    private static double relevanceScore(MemoryEntry e, String query) {
        if (query == null || query.isBlank()) return 1.0;
        Set<String> queryWords = Arrays.stream(query.toLowerCase().split("\\W+"))
                .filter(w -> w.length() > 2)
                .collect(Collectors.toSet());
        if (queryWords.isEmpty()) return 1.0;
        Set<String> contentWords = Arrays.stream(e.content().toLowerCase().split("\\W+"))
                .collect(Collectors.toSet());
        long matches = queryWords.stream().filter(contentWords::contains).count();
        return 0.5 + 0.5 * Math.min(1.0, matches / (double) queryWords.size());
    }
}
