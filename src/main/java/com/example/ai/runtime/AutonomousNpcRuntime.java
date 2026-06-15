package com.example.ai.runtime;

import com.example.ai.action.AgentActionExecutor;
import com.example.ai.intent.AgentDecision;
import com.example.ai.intent.AgentDecisionParser;
import com.example.ai.intent.AgentIntentType;
import com.example.ai.llm.LlmRouter;
import com.example.ai.memory.AgentMemoryStore;
import com.example.ai.memory.MemoryContext;
import com.example.ai.memory.MemoryEntry;
import com.example.ai.perception.SemanticPerceptionService;
import com.example.ai.perception.WorldSnapshot;
import com.example.ai.prompt.PromptFactory;
import com.example.ai.safety.SafetyPolicy;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AutonomousNpcRuntime {
    private final Logger logger;
    private final SemanticPerceptionService perceptionService;
    private final PromptFactory promptFactory;
    private final LlmRouter llmRouter;
    private final AgentDecisionParser decisionParser;
    private final AgentActionExecutor actionExecutor;
    private final AgentMemoryStore memoryStore;
    private final SafetyPolicy safetyPolicy;
    private final NpcBindingStore bindingStore;

    private final Map<UUID, AgentHandle> agents = new ConcurrentHashMap<>();
    private final Map<UUID, Long> nextThinkAt = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> pendingInstruction = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> llmInFlight = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastParsedDecisionIntentByNpc = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastProcessedInstructionByNpc = new ConcurrentHashMap<>();

    private final ExecutorService llmExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<UUID> thinkingNpcs = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingWelcomeBack = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> villagerFirstMissingAt = new ConcurrentHashMap<>();
    private static final long VILLAGER_MISSING_EVICT_MS = 30_000L;

    private AutonomousNpcRuntime(
            Logger logger,
            SemanticPerceptionService perceptionService,
            PromptFactory promptFactory,
            LlmRouter llmRouter,
            AgentDecisionParser decisionParser,
            AgentActionExecutor actionExecutor,
            AgentMemoryStore memoryStore,
            SafetyPolicy safetyPolicy,
            NpcBindingStore bindingStore
    ) {
        this.logger = logger;
        this.perceptionService = perceptionService;
        this.promptFactory = promptFactory;
        this.llmRouter = llmRouter;
        this.decisionParser = decisionParser;
        this.actionExecutor = actionExecutor;
        this.memoryStore = memoryStore;
        this.safetyPolicy = safetyPolicy;
        this.bindingStore = bindingStore;
    }

    public static AutonomousNpcRuntime createDefault(Logger logger) {
        var memoryStore = AgentMemoryStore.createDefault(logger);
        var bindingStore = NpcBindingStore.createDefault(logger);
        var promptFactory = new PromptFactory();
        var llmRouter = LlmRouter.defaultRouter();
        return new AutonomousNpcRuntime(
                logger,
                new SemanticPerceptionService(),
                promptFactory,
                llmRouter,
                new AgentDecisionParser(),
                new AgentActionExecutor(logger, promptFactory, llmRouter),
                memoryStore,
                SafetyPolicy.defaultPolicy(),
                bindingStore
        );
    }

    public void onServerTick(MinecraftServer server) {
        long now = System.currentTimeMillis();
        restorePersistedBindings(server);
        List<UUID> toEvict = null;
        for (AgentHandle handle : agents.values()) {
            UUID npcId = handle.npcId();

            if (!actionExecutor.isVillagerAlive(server, npcId)) {
                long firstMissing = villagerFirstMissingAt.computeIfAbsent(npcId, k -> now);
                if (now - firstMissing >= VILLAGER_MISSING_EVICT_MS) {
                    logger.warn("[AGENT] Villager {} ({}) missing for {}s — evicting binding",
                            handle.npcName(), npcId, VILLAGER_MISSING_EVICT_MS / 1000);
                    if (toEvict == null) toEvict = new ArrayList<>();
                    toEvict.add(npcId);
                }
                continue;
            }
            villagerFirstMissingAt.remove(npcId);

            actionExecutor.enforceOwnerLeash(server, npcId, handle.ownerPlayerId(), handle.npcName());
            actionExecutor.applyNextAction(server, npcId, handle.npcName());

            List<String> summaries = actionExecutor.pollCompletedTaskSummaries(npcId);
            if (summaries != null) {
                for (String summary : summaries) {
                    memoryStore.appendLongTerm(npcId, MemoryEntry.episodic("task_result", summary));
                }
            }

            if (thinkingNpcs.contains(npcId)) continue;

            // Fire welcome-back greeting once after server restart, before normal processing
            if (pendingWelcomeBack.contains(npcId)) {
                MemoryContext greetMem = memoryStore.getContext(npcId);
                thinkingNpcs.add(npcId);
                llmExecutor.submit(() -> {
                    try {
                        boolean fired = actionExecutor.tryFireWelcomeBack(
                                server, npcId, handle.npcName(), handle.ownerPlayerId(), greetMem);
                        if (fired) pendingWelcomeBack.remove(npcId);
                    } catch (Exception e) {
                        logger.warn("[ASYNC] Welcome-back error for {}", handle.npcName(), e);
                    } finally {
                        thinkingNpcs.remove(npcId);
                    }
                });
                continue;
            }

            if (now < nextThinkAt.getOrDefault(npcId, 0L)) continue;

            MemoryContext memory       = memoryStore.getContext(npcId);
            boolean hasPending         = pendingInstruction.getOrDefault(npcId, false);
            WorldSnapshot snapshot     = actionExecutor.captureWorldSnapshot(server, npcId, handle.npcName());

            if (!hasPending) {

                thinkingNpcs.add(npcId);
                llmExecutor.submit(() -> {
                    try {
                        actionExecutor.maybeSendEnvironmentalAdvisory(
                                server, npcId, handle.npcName(), handle.ownerPlayerId(), memory);
                    } catch (Exception e) {
                        logger.warn("[ASYNC] Environmental advisory error for {}", handle.npcName(), e);
                    } finally {
                        thinkingNpcs.remove(npcId);
                    }
                });
                continue;
            }

          
            String latestEntry  = latestInstruction(memory);
            String speaker      = speakerFromEntry(latestEntry);
            String instruction  = instructionTextFromEntry(latestEntry);

            thinkingNpcs.add(npcId);
            llmExecutor.submit(() -> {
                try {
                    processInstruction(server, handle, npcId, memory, snapshot,
                            speaker, instruction, now, hasPending);
                } catch (Exception e) {
                    logger.warn("[ASYNC] Think error for {}", handle.npcName(), e);
                    pendingInstruction.put(npcId, false);
                    nextThinkAt.put(npcId, System.currentTimeMillis() + 1200L);
                } finally {
                    thinkingNpcs.remove(npcId);
                }
            });
        }
        if (toEvict != null) {
            for (UUID npcId : toEvict) {
                agents.remove(npcId);
                nextThinkAt.remove(npcId);
                pendingInstruction.remove(npcId);
                pendingWelcomeBack.remove(npcId);
                thinkingNpcs.remove(npcId);
                villagerFirstMissingAt.remove(npcId);
                bindingStore.forgetBinding(npcId);
            }
        }
    }

    private void processInstruction(MinecraftServer server, AgentHandle handle, UUID npcId,
                                    MemoryContext memory, WorldSnapshot snapshot,
                                    String speaker, String latestInstruction,
                                    long now, boolean hasPendingInstruction) {
        String lastInstruction = lastProcessedInstructionByNpc.get(npcId);
        if (lastInstruction != null && !lastInstruction.isBlank()
                && isChallenge(lastInstruction, latestInstruction)) {
            logger.info("[THINK] npc={} challenge detected — replaying last instruction='{}'",
                    handle.npcName(), lastInstruction);
            latestInstruction = lastInstruction;
        }

        logger.info("[THINK] npc={} instruction='{}' speaker='{}'",
                handle.npcName(), latestInstruction, speaker);

        // Self-classifying handlers — each uses its own LLM to decide if it applies
        if (actionExecutor.tryHandleRecipeInstruction(
                    server, npcId, handle.npcName(), speaker, handle.ownerPlayerId(),
                    latestInstruction, memory, snapshot)) {
                pendingInstruction.put(npcId, false);
                nextThinkAt.put(npcId, now + 800L);
                storeActionMemory(npcId, speaker, latestInstruction, "recipe");
                return;
            }

            if (actionExecutor.tryHandleSearchInstruction(
                    server, npcId, handle.npcName(), speaker, handle.ownerPlayerId(),
                    latestInstruction, memory)) {
                pendingInstruction.put(npcId, false);
                nextThinkAt.put(npcId, now + 800L);
                storeActionMemory(npcId, speaker, latestInstruction, "search");
                return;
            }

            if (actionExecutor.tryHandleScoutInstruction(
                    server, npcId, handle.npcName(), speaker, handle.ownerPlayerId(),
                    latestInstruction, memory, snapshot)) {
                pendingInstruction.put(npcId, false);
                nextThinkAt.put(npcId, now + 800L);
                storeActionMemory(npcId, speaker, latestInstruction, "scout");
                return;
        }

        // Action selection LLM decides intent — no keyword pre-classification
        String prompt = promptFactory.actionSelectionPrompt(
                snapshot, memory, hasPendingInstruction, latestInstruction, "", "");

        llmInFlight.put(npcId, true);
        String raw;
        try {
            raw = llmRouter.generate(prompt);
        } finally {
            llmInFlight.put(npcId, false);
        }

        AgentDecision decision = decisionParser.parse(raw);
        lastParsedDecisionIntentByNpc.put(npcId, decision.intent().name().toLowerCase());

        if (!decision.isValid()) {
            logger.info("LLM produced invalid decision for agent {}: {}", handle.npcName(), raw);
            nextThinkAt.put(npcId, now + 1200L);
            return;
        }
        if (!safetyPolicy.allow(npcId, decision)) {
            logger.info("Safety policy blocked decision for agent {}: {}", handle.npcName(), decision.intent());
            nextThinkAt.put(npcId, now + 1200L);
            return;
        }
        if (decision.intent() == AgentIntentType.IDLE) {
            logger.info("Idle decision rejected for pending instruction on agent {}. Retrying.", handle.npcName());
            nextThinkAt.put(npcId, now + 1200L);
            return;
        }
        if (decision.intent() == AgentIntentType.DIALOGUE_REPLY && !decision.parameters().has("text")) {
            logger.info("Dialogue decision missing parameters.text for agent {}. Retrying.", handle.npcName());
            nextThinkAt.put(npcId, now + 1200L);
            return;
        }
        if (decision.intent() == AgentIntentType.FETCH_FROM_CHEST && !decision.parameters().has("item_id")) {
            logger.info("Fetch decision missing parameters.item_id for agent {}. Retrying.", handle.npcName());
            nextThinkAt.put(npcId, now + 1200L);
            return;
        }
        if (decision.intent() == AgentIntentType.MINE_BLOCK
                && !decision.parameters().has("block")
                && !(decision.parameters().has("x") && decision.parameters().has("y") && decision.parameters().has("z"))) {
            logger.info("Mine decision missing block target for agent {}. Retrying.", handle.npcName());
            nextThinkAt.put(npcId, now + 1200L);
            return;
        }
        if (decision.intent() == AgentIntentType.MINE_TO_CHEST && !decision.parameters().has("block")) {
            logger.info("Mine-to-chest decision missing parameters.block for agent {}. Retrying.", handle.npcName());
            nextThinkAt.put(npcId, now + 1200L);
            return;
        }
        if (decision.intent() == AgentIntentType.MINE_TO_PLAYER && !decision.parameters().has("block")) {
            logger.info("Mine-to-player decision missing parameters.block for agent {}. Retrying.", handle.npcName());
            nextThinkAt.put(npcId, now + 1200L);
            return;
        }

        // Post-classification dispatch for handlers that need specialized execution
        if (decision.intent() == AgentIntentType.BUILD_STRUCTURE) {
            if (actionExecutor.tryHandleScenarioInstruction(
                    server, npcId, handle.npcName(), speaker, handle.ownerPlayerId(),
                    latestInstruction, memory, snapshot, true)) {
                pendingInstruction.put(npcId, false);
                nextThinkAt.put(npcId, now + 800L);
                storeActionMemory(npcId, speaker, latestInstruction, "scenario");
                return;
            }
        }

        if (decision.intent() == AgentIntentType.DIALOGUE_REPLY) {
            if (actionExecutor.tryHandleDialogueInstruction(
                    server, npcId, handle.npcName(), speaker, handle.ownerPlayerId(),
                    latestInstruction, memory, snapshot)) {
                pendingInstruction.put(npcId, false);
                nextThinkAt.put(npcId, now + 800L);
                memoryStore.appendLongTerm(npcId, MemoryEntry.episodic("dialogue",
                        "Handled dialogue from " + speaker + ": " + latestInstruction));
                lastProcessedInstructionByNpc.put(npcId, latestInstruction);
                return;
            }
        }

        if (decision.intent().name().startsWith("TRADE_")) {
            if (actionExecutor.tryHandleTradeInstruction(
                    server, npcId, handle.npcName(), speaker, handle.ownerPlayerId(),
                    latestInstruction, memory, snapshot)) {
                pendingInstruction.put(npcId, false);
                nextThinkAt.put(npcId, now + 800L);
                memoryStore.appendLongTerm(npcId, MemoryEntry.episodic("trade",
                        "Handled trade instruction from " + speaker + ": " + latestInstruction));
                lastProcessedInstructionByNpc.put(npcId, latestInstruction);
                return;
            }
        }

        actionExecutor.execute(npcId, decision);
        logger.info("LLM decision queued: agent={} intent={} priority={} reasoning={}",
                handle.npcName(), decision.intent(), decision.priority(), decision.reasoning());
        memoryStore.appendWorking(npcId, MemoryEntry.working(decision, true));
        memoryStore.appendLongTerm(npcId, MemoryEntry.episodic("decision",
                "Executed " + decision.intent().name().toLowerCase() + ": " + decision.reasoning()));
        pendingInstruction.put(npcId, false);
        nextThinkAt.put(npcId, now + 1500L);
        lastProcessedInstructionByNpc.put(npcId, latestInstruction);
    }

    public void registerAgent(UUID npcId, String npcName) {
        registerAgent(npcId, npcName, null);
    }

    public void registerAgent(UUID npcId, String npcName, UUID ownerPlayerId) {
        agents.put(npcId, new AgentHandle(npcId, npcName, ownerPlayerId));
        pendingInstruction.put(npcId, false);
        bindingStore.rememberBinding(npcId, npcName, ownerPlayerId);
        logger.info("Registered embodied AI agent {}", npcName);
    }

    public void enqueuePlayerUtterance(UUID npcId, String playerName, String text) {
        memoryStore.appendShortTerm(npcId, MemoryEntry.dialogue(playerName, text));
        pendingInstruction.put(npcId, true);
        nextThinkAt.put(npcId, 0L);
    }

    public boolean isAgentRegistered(UUID npcId) {
        return agents.containsKey(npcId);
    }

    public void updateTradePrices(java.util.Map<String, com.example.ai.trade.PriceConfig> configs) {
        actionExecutor.updateTradePrices(configs);
    }

    public java.util.Map<String, com.example.ai.trade.PriceConfig> currentTradePriceConfigs() {
        return actionExecutor.currentTradePriceConfigs();
    }

    public String statusForAgent(UUID npcId) {
        AgentHandle handle = agents.get(npcId);
        if (handle == null) {
            return "registered=false";
        }
        boolean pending = pendingInstruction.getOrDefault(npcId, false);
        boolean actionLlmBusy = llmInFlight.getOrDefault(npcId, false);
        boolean tradeLlmBusy = actionExecutor.isTradeLlmInFlight(npcId);
        String phase = actionExecutor.currentTaskPhase(npcId);
        String offer = actionExecutor.activeOfferSummary(npcId, handle.ownerPlayerId());
        String lastTradeIntent = actionExecutor.lastTradeIntent(npcId);
        return "registered=true"
                + " | npc=" + handle.npcName()
                + " | phase=" + phase
                + " | active_offer=" + offer
                + " | pending_instruction=" + pending
                + " | pending_llm_calls=" + (actionLlmBusy || tradeLlmBusy)
                + " | last_parser_intent=" + lastTradeIntent;
    }

    public List<String> debugForAgent(UUID npcId) {
        List<String> lines = new ArrayList<>();
        AgentHandle handle = agents.get(npcId);
        if (handle == null) {
            lines.add("registered=false");
            return lines;
        }
        long now = System.currentTimeMillis();
        long nextAt = nextThinkAt.getOrDefault(npcId, 0L);
        long waitMs = Math.max(0L, nextAt - now);
        MemoryContext context = memoryStore.getContext(npcId);

        lines.add(statusForAgent(npcId));
        lines.add("owner_player_id=" + (handle.ownerPlayerId() == null ? "none" : handle.ownerPlayerId()));
        lines.add("next_think_in_ms=" + waitMs);
        lines.add("last_decision_intent=" + lastParsedDecisionIntentByNpc.getOrDefault(npcId, "none"));
        lines.add("memory_sizes short=" + context.shortTerm().size()
                + " working=" + context.working().size()
                + " long=" + context.longTerm().size());
        return lines;
    }

    private String latestInstruction(MemoryContext memory) {
        if (memory.shortTerm().isEmpty()) {
            return "";
        }
        return memory.shortTerm().get(memory.shortTerm().size() - 1).content();
    }

    private String speakerFromEntry(String entry) {
        if (entry == null || entry.isBlank()) {
            return "";
        }
        int sep = entry.indexOf(':');
        if (sep <= 0) {
            return "";
        }
        return entry.substring(0, sep).trim();
    }

    private String instructionTextFromEntry(String entry) {
        if (entry == null || entry.isBlank()) {
            return "";
        }
        int sep = entry.indexOf(':');
        if (sep < 0 || sep + 1 >= entry.length()) {
            return entry.trim();
        }
        return entry.substring(sep + 1).trim();
    }

    private void restorePersistedBindings(MinecraftServer server) {
        for (Map.Entry<UUID, NpcBindingStore.BoundNpc> entry : bindingStore.snapshot().entrySet()) {
            UUID npcId = entry.getKey();
            if (agents.containsKey(npcId)) {
                continue;
            }

            Entity entity = null;
            for (ServerLevel level : server.getAllLevels()) {
                entity = level.getEntity(npcId);
                if (entity != null) {
                    break;
                }
            }
            if (!(entity instanceof Villager villager) || !villager.isAlive()) {
                continue;
            }

            String npcName = villager.getName().getString();
            if (npcName == null || npcName.isBlank()) {
                npcName = entry.getValue().npcName();
            }
            UUID ownerPlayerId = null;
            String ownerPlayerIdText = entry.getValue().ownerPlayerId();
            if (ownerPlayerIdText != null && !ownerPlayerIdText.isBlank()) {
                try {
                    ownerPlayerId = UUID.fromString(ownerPlayerIdText);
                } catch (IllegalArgumentException ignored) {
                    ownerPlayerId = null;
                }
            }

            agents.put(npcId, new AgentHandle(npcId, npcName, ownerPlayerId));
            pendingInstruction.put(npcId, false);
            pendingWelcomeBack.add(npcId);
            logger.info("Restored embodied AI agent {} from saved binding", npcName);
        }
    }

    private boolean isChallenge(String lastInstruction, String currentInstruction) {
        try {
            String prompt = promptFactory.challengeClassifierPrompt(lastInstruction, currentInstruction);
            String raw = llmRouter.generate(prompt);
            if (raw == null || raw.isBlank()) return false;
            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}');
            if (start < 0 || end <= start) return false;
            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(
                    raw.substring(start, end + 1)).getAsJsonObject();
            return root.has("is_challenge") && root.get("is_challenge").getAsBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    private void storeActionMemory(UUID npcId, String speaker, String instruction, String type) {
        String summary = actionExecutor.lastActionSummary(npcId);
        String entry = summary.isBlank()
                ? speaker + " asked me to " + type + ": " + instruction
                : summary + " (requested by " + speaker + ")";
        memoryStore.appendWorking(npcId, MemoryEntry.episodic(type, entry));
        memoryStore.appendLongTerm(npcId, MemoryEntry.episodic(type, entry));
        lastProcessedInstructionByNpc.put(npcId, instruction);
    }

    private record AgentHandle(UUID npcId, String npcName, UUID ownerPlayerId) {
    }

}
