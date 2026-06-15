package com.example.ai.memory;

import org.slf4j.Logger;

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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AgentMemoryStore {
    private static final int SHORT_TERM_MAX = 12;
    private static final int WORKING_MAX = 16;
    private static final int LONG_TERM_MAX = 128;

    private final Logger logger;
    private final Path dbPath;
    private Connection connection;

    private final Map<UUID, List<MemoryEntry>> shortTerm = new ConcurrentHashMap<>();
    private final Map<UUID, List<MemoryEntry>> working = new ConcurrentHashMap<>();
    private final Map<UUID, List<MemoryEntry>> longTermCache = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> loaded = new ConcurrentHashMap<>();

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
                            id        INTEGER PRIMARY KEY AUTOINCREMENT,
                            npc_uuid  TEXT    NOT NULL,
                            type      TEXT    NOT NULL,
                            timestamp INTEGER NOT NULL,
                            content   TEXT    NOT NULL,
                            salience  REAL    NOT NULL
                        )""");
                stmt.execute(
                        "CREATE INDEX IF NOT EXISTS idx_npc ON long_term_memory(npc_uuid)");
            }
        } catch (Exception e) {
            logger.error("Failed to initialise SQLite memory database at {}", dbPath, e);
        }
    }

    public void appendShortTerm(UUID npcId, MemoryEntry entry) {
        appendRam(shortTerm, npcId, entry, SHORT_TERM_MAX);
    }

    public void appendWorking(UUID npcId, MemoryEntry entry) {
        appendRam(working, npcId, entry, WORKING_MAX);
    }

    public void appendLongTerm(UUID npcId, MemoryEntry entry) {
        ensureLoaded(npcId);
        appendRam(longTermCache, npcId, entry, LONG_TERM_MAX);
        persistLongTerm(npcId, entry);
    }

    public MemoryContext getContext(UUID npcId) {
        ensureLoaded(npcId);
        List<MemoryEntry> shortList   = new ArrayList<>(shortTerm.getOrDefault(npcId, List.of()));
        List<MemoryEntry> workingList = new ArrayList<>(working.getOrDefault(npcId, List.of()));
        List<MemoryEntry> longList    = longTermCache.getOrDefault(npcId, List.of()).stream()
                .sorted(Comparator.comparingDouble(MemoryEntry::salience).reversed())
                .limit(8)
                .toList();
        return new MemoryContext(shortList, workingList, longList);
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

    private void persistLongTerm(UUID npcId, MemoryEntry entry) {
        if (connection == null) return;
        try {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO long_term_memory (npc_uuid, type, timestamp, content, salience) VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, npcId.toString());
                ps.setString(2, entry.type());
                ps.setLong(3,   entry.timestamp());
                ps.setString(4, entry.content());
                ps.setDouble(5, entry.salience());
                ps.executeUpdate();
            }
            // Enforce cap: keep only the most recent LONG_TERM_MAX rows per NPC
            try (PreparedStatement ps = connection.prepareStatement("""
                    DELETE FROM long_term_memory
                    WHERE npc_uuid = ?
                      AND id NOT IN (
                          SELECT id FROM long_term_memory
                          WHERE npc_uuid = ?
                          ORDER BY timestamp DESC
                          LIMIT ?
                      )""")) {
                ps.setString(1, npcId.toString());
                ps.setString(2, npcId.toString());
                ps.setInt(3,    LONG_TERM_MAX);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.warn("Failed to persist long-term memory for {}", npcId, e);
        }
    }

    private void ensureLoaded(UUID npcId) {
        if (loaded.putIfAbsent(npcId, true) != null) return;
        if (connection == null) return;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT type, timestamp, content, salience FROM long_term_memory WHERE npc_uuid = ? ORDER BY timestamp ASC")) {
            ps.setString(1, npcId.toString());
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
            longTermCache.put(npcId, entries);
        } catch (SQLException e) {
            logger.warn("Failed to load long-term memory for {}", npcId, e);
        }
    }
}
