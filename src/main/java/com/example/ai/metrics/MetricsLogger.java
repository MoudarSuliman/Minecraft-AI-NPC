package com.example.ai.metrics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;

public final class MetricsLogger {

    private static final Path CSV       = Path.of("config", "llm_npc", "metrics.csv");
    private static final Path TEST_CSV  = Path.of("config", "llm_npc", "intent_tests.csv");

    private static final String CSV_HEADER      = "timestamp,npc_id,scenario,latency_ms,success\n";
    private static final String TEST_CSV_HEADER = "timestamp,npc_id,expected_intent,actual_intent,latency_ms,intent_correct\n";

    private MetricsLogger() {}

    public static synchronized void record(UUID npcId, String scenario, long startMs, boolean success) {
        long latencyMs = System.currentTimeMillis() - startMs;
        String line = Instant.now() + ","
                + npcId + ","
                + scenario + ","
                + latencyMs + ","
                + (success ? "1" : "0")
                + "\n";
        append(CSV, CSV_HEADER, line);
    }

    public static synchronized void recordTest(UUID npcId, String expected, String actual, long startMs) {
        long latencyMs = System.currentTimeMillis() - startMs;
        boolean correct = expected.equalsIgnoreCase(actual);
        String line = Instant.now() + ","
                + npcId + ","
                + expected + ","
                + actual + ","
                + latencyMs + ","
                + (correct ? "1" : "0")
                + "\n";
        append(TEST_CSV, TEST_CSV_HEADER, line);
    }

    private static void append(Path file, String header, String line) {
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, header);
            }
            Files.writeString(file, line, StandardOpenOption.APPEND);
        } catch (IOException ignored) {}
    }
}
