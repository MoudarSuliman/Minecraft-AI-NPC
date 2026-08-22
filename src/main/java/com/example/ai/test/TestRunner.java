package com.example.ai.test;

import com.example.ai.metrics.MetricsLogger;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class TestRunner {

    public record TestCase(String instruction, String expectedIntent, String category) {}

    private static final List<TestCase> CASES = List.of(
        new TestCase("hello, what can you do?",                    "dialogue_reply",  "dialogue"),
        new TestCase("how are you doing today?",                   "dialogue_reply",  "dialogue"),
        new TestCase("what can you help me with?",                 "dialogue_reply",  "dialogue"),
        new TestCase("tell me something interesting",              "dialogue_reply",  "dialogue"),
        new TestCase("what do you think about this place?",        "dialogue_reply",  "dialogue"),
        new TestCase("what do I need to craft a diamond pickaxe?", "recipe_reply",    "recipe"),
        new TestCase("how do I make a furnace?",                   "recipe_reply",    "recipe"),
        new TestCase("what ingredients do I need for a bow?",      "recipe_reply",    "recipe"),
        new TestCase("can you tell me the recipe for a chest?",    "recipe_reply",    "recipe"),
        new TestCase("how do I craft oak planks?",                 "recipe_reply",    "recipe"),
        new TestCase("find me a chicken",                          "search_entity",   "search"),
        new TestCase("go find a cow nearby",                       "search_entity",   "search"),
        new TestCase("look for a sheep around here",               "search_entity",   "search"),
        new TestCase("can you locate a pig for me?",               "search_entity",   "search"),
        new TestCase("search for a creeper",                       "search_entity",   "search"),
        new TestCase("go scout east and tell me what you see",     "scout_explorer",  "scout"),
        new TestCase("explore north and report back",              "scout_explorer",  "scout"),
        new TestCase("head south and look around",                 "scout_explorer",  "scout"),
        new TestCase("scout west for me",                          "scout_explorer",  "scout"),
        new TestCase("go check out what is to the east",           "scout_explorer",  "scout"),
        new TestCase("build me a 3x3 cobblestone floor",           "build_structure", "build"),
        new TestCase("construct a small cobblestone hut",          "build_structure", "build"),
        new TestCase("make a cobblestone wall",                    "build_structure", "build"),
        new TestCase("build a 5x5 platform out of cobblestone",    "build_structure", "build"),
        new TestCase("can you build me a simple shelter",          "build_structure", "build"),
        new TestCase("can you sell me some oak logs?",             "trade_offer",     "trade"),
        new TestCase("I want to buy iron ingots",                  "trade_offer",     "trade"),
        new TestCase("what is the price of oak logs?",             "trade_offer",     "trade"),
        new TestCase("I would like to trade with you",             "trade_offer",     "trade"),
        new TestCase("do you have any items for sale?",            "dialogue_reply",  "trade_general")
    );

    private enum Phase { DELAY, WAITING, DONE }

    private final Logger logger;
    private final UUID npcId;
    private final ServerPlayer requester;
    private final Supplier<String> lastIntentGetter;
    private final Supplier<Boolean> isIdleChecker;
    private final Supplier<Boolean> isAliveChecker;
    private final Consumer<String> instructionSubmitter;
    private final Runnable offerClearer;

    private static final int MAX_RETRIES = 3;
    private static final long RATE_LIMIT_LATENCY_THRESHOLD_MS = 1_500L;
    private static final long RETRY_GAP_MS = 6_000L;
    private static final long CASE_TIMEOUT_MS = 60_000L;

    private Phase phase = Phase.DELAY;
    private int caseIndex = 0;
    private int retryCount = 0;
    private int timeoutCount = 0;
    private long startMs = 0L;
    private long resumeAt = 0L;
    private boolean finished = false;

    public TestRunner(
            Logger logger,
            UUID npcId,
            ServerPlayer requester,
            Supplier<String> lastIntentGetter,
            Supplier<Boolean> isIdleChecker,
            Supplier<Boolean> isAliveChecker,
            Consumer<String> instructionSubmitter,
            Runnable offerClearer) {
        this.logger = logger;
        this.npcId = npcId;
        this.requester = requester;
        this.lastIntentGetter = lastIntentGetter;
        this.isIdleChecker = isIdleChecker;
        this.isAliveChecker = isAliveChecker;
        this.instructionSubmitter = instructionSubmitter;
        this.offerClearer = offerClearer;
    }

    public boolean isFinished() {
        return finished;
    }

    public void tick() {
        if (finished) return;
        long now = System.currentTimeMillis();

        if (!isAliveChecker.get()) {
            abort("NPC is no longer alive (killed or despawned)");
            return;
        }

        if (phase == Phase.DELAY) {
            if (now < resumeAt) return;
            if (caseIndex >= CASES.size()) {
                finish();
                return;
            }
            TestCase tc = CASES.get(caseIndex);
            send(String.format("[TEST %d/%d] Sending: \"%s\"", caseIndex + 1, CASES.size(), tc.instruction()));
            offerClearer.run();
            startMs = now;
            instructionSubmitter.accept(tc.instruction());
            phase = Phase.WAITING;
        }

        if (phase == Phase.WAITING) {
            if (now - startMs < 800) return;   // give the LLM call time to start

            if (!isIdleChecker.get()) {
                if (now - startMs < CASE_TIMEOUT_MS) return;   // still processing
                TestCase timedOut = CASES.get(caseIndex);
                timeoutCount++;
                MetricsLogger.recordTest(npcId, timedOut.expectedIntent(), "timeout", startMs);
                send(String.format("[TEST %d/%d] TIMEOUT | expected=%s no result after %ds",
                        caseIndex + 1, CASES.size(), timedOut.expectedIntent(), CASE_TIMEOUT_MS / 1000));
                logger.warn("[TEST] run={} TIMEOUT category={} expected={} after {}ms",
                        caseIndex + 1, timedOut.category(), timedOut.expectedIntent(), now - startMs);
                retryCount = 0;
                caseIndex++;
                resumeAt = now + 3_000L;
                phase = Phase.DELAY;
                return;
            }

            long latency = now - startMs;
            TestCase tc = CASES.get(caseIndex);
            String actual = lastIntentGetter.get();

            // Detect rate-limit hit: idle result returned in under threshold → retry
            boolean rateLimitHit = "idle".equalsIgnoreCase(actual) && latency < RATE_LIMIT_LATENCY_THRESHOLD_MS;
            if (rateLimitHit && retryCount < MAX_RETRIES) {
                retryCount++;
                send(String.format("[TEST %d/%d] Rate-limited (idle in %dms), retry %d/%d in 6s",
                        caseIndex + 1, CASES.size(), latency, retryCount, MAX_RETRIES));
                logger.info("[TEST] run={} rate-limited, retry {}/{}", caseIndex + 1, retryCount, MAX_RETRIES);
                resumeAt = now + RETRY_GAP_MS;
                phase = Phase.DELAY;
                return;
            }

            retryCount = 0;
            boolean pass = tc.expectedIntent().equalsIgnoreCase(actual);
            MetricsLogger.recordTest(npcId, tc.expectedIntent(), actual != null ? actual : "null", startMs);

            String status = pass ? "PASS" : "FAIL";
            send(String.format("[TEST %d/%d] %s | expected=%s actual=%s latency=%dms",
                    caseIndex + 1, CASES.size(), status, tc.expectedIntent(), actual, latency));
            logger.info("[TEST] run={} {} category={} expected={} actual={} latency={}ms",
                    caseIndex + 1, status, tc.category(), tc.expectedIntent(), actual, latency);

            caseIndex++;
            resumeAt = now + 3_000L;  // 3-second gap between cases
            phase = Phase.DELAY;
        }
    }

    public void cancel() {
        if (finished) return;
        finished = true;
        send(String.format("[TEST] Cancelled at case %d/%d. Partial results written to config/llm_npc/intent_tests.csv",
                Math.min(caseIndex + 1, CASES.size()), CASES.size()));
        logger.info("[TEST] Test run cancelled by request at case {}/{}.", caseIndex + 1, CASES.size());
    }

    private void abort(String reason) {
        finished = true;
        send(String.format("[TEST] Aborted at case %d/%d: %s. Partial results written to config/llm_npc/intent_tests.csv",
                Math.min(caseIndex + 1, CASES.size()), CASES.size(), reason));
        logger.warn("[TEST] Test run aborted at case {}/{}: {}", caseIndex + 1, CASES.size(), reason);
    }

    private void finish() {
        finished = true;
        String suffix = timeoutCount > 0 ? String.format(" (%d timed out)", timeoutCount) : "";
        send("[TEST] All " + CASES.size() + " cases complete" + suffix
                + ". Results written to config/llm_npc/intent_tests.csv");
        logger.info("[TEST] Test run complete. {} cases executed, {} timed out.", CASES.size(), timeoutCount);
    }

    private void send(String msg) {
        if (requester != null && requester.isAlive()) {
            requester.sendSystemMessage(Component.literal(msg));
        }
    }
}
