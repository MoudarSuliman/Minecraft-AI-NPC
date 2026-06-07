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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
    private final Map<UUID, String> lastDemandByNpc = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastExpectedIntentByNpc = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastParsedDecisionIntentByNpc = new ConcurrentHashMap<>();

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
        for (AgentHandle handle : agents.values()) {
            UUID npcId = handle.npcId();
            MemoryContext memory = memoryStore.getContext(npcId);
            actionExecutor.enforceOwnerLeash(server, npcId, handle.ownerPlayerId(), handle.npcName());
            actionExecutor.maybeSendTradeGreeting(server, npcId, handle.npcName(), handle.ownerPlayerId(), memory);
            actionExecutor.maybeSendEnvironmentalAdvisory(server, npcId, handle.npcName(), handle.ownerPlayerId(), memory);
            actionExecutor.applyNextAction(server, npcId, handle.npcName());

            boolean hasPendingInstruction = pendingInstruction.getOrDefault(npcId, false);
            if (!hasPendingInstruction) {
                continue;
            }
            if (now < nextThinkAt.getOrDefault(npcId, 0L)) {
                continue;
            }

            String latestEntry = latestInstruction(memory);
            String speaker = speakerFromEntry(latestEntry);
            String latestInstruction = instructionTextFromEntry(latestEntry);
            WorldSnapshot snapshot = actionExecutor.captureWorldSnapshot(server, npcId, handle.npcName());

            if (actionExecutor.tryHandleRecipeInstruction(
                    server,
                    npcId,
                    handle.npcName(),
                    speaker,
                    handle.ownerPlayerId(),
                    latestInstruction,
                    memory,
                    snapshot
            )) {
                pendingInstruction.put(npcId, false);
                nextThinkAt.put(npcId, now + 800L);
                memoryStore.appendLongTerm(
                        npcId,
                        MemoryEntry.episodic("recipe", "Handled recipe instruction from " + speaker + ": " + latestInstruction)
                );
                continue;
            }

            if (actionExecutor.tryHandleSearchInstruction(
                    server,
                    npcId,
                    handle.npcName(),
                    speaker,
                    handle.ownerPlayerId(),
                    latestInstruction
            )) {
                pendingInstruction.put(npcId, false);
                nextThinkAt.put(npcId, now + 800L);
                memoryStore.appendLongTerm(
                        npcId,
                        MemoryEntry.episodic("search", "Handled search instruction from " + speaker + ": " + latestInstruction)
                );
                continue;
            }

            if (actionExecutor.tryHandleTradeInstruction(
                    server,
                    npcId,
                    handle.npcName(),
                    speaker,
                    handle.ownerPlayerId(),
                    latestInstruction,
                    memory,
                    snapshot
            )) {
                pendingInstruction.put(npcId, false);
                nextThinkAt.put(npcId, now + 800L);
                memoryStore.appendLongTerm(
                        npcId,
                        MemoryEntry.episodic("trade", "Handled trade instruction from " + speaker + ": " + latestInstruction)
                );
                continue;
            }

            InstructionDemand demand = classifyDemand(latestInstruction);
            String expectedIntent = expectedIntentForDemand(demand);
            lastDemandByNpc.put(npcId, demand.name().toLowerCase(Locale.ROOT));
            lastExpectedIntentByNpc.put(npcId, expectedIntent.isBlank() ? "none" : expectedIntent);
            String targetHint = targetHintFromInstruction(latestInstruction, demand);
            String prompt = promptFactory.actionSelectionPrompt(
                    snapshot,
                    memory,
                    hasPendingInstruction,
                    latestInstruction,
                    expectedIntent,
                    targetHint
            );
            llmInFlight.put(npcId, true);
            String raw;
            try {
                raw = llmRouter.generate(prompt);
            } finally {
                llmInFlight.put(npcId, false);
            }
            AgentDecision decision = decisionParser.parse(raw);
            lastParsedDecisionIntentByNpc.put(npcId, decision.intent().name().toLowerCase(Locale.ROOT));
            if (!decision.isValid()) {
                logger.info("LLM produced invalid decision for agent {}: {}", handle.npcName(), raw);
                nextThinkAt.put(npcId, now + 1200L);
                continue;
            }
            if (!safetyPolicy.allow(npcId, decision)) {
                logger.info("Safety policy blocked decision for agent {}: {}", handle.npcName(), decision.intent());
                nextThinkAt.put(npcId, now + 1200L);
                continue;
            }

            if (decision.intent() == AgentIntentType.IDLE) {
                logger.info("Idle decision rejected for pending instruction on agent {}. Retrying.", handle.npcName());
                nextThinkAt.put(npcId, now + 1200L);
                continue;
            }
            if (decision.intent() == AgentIntentType.DIALOGUE_REPLY
                    && !decision.parameters().has("text")) {
                logger.info("Dialogue decision missing parameters.text for agent {}. Retrying.", handle.npcName());
                nextThinkAt.put(npcId, now + 1200L);
                continue;
            }
            if (decision.intent() == AgentIntentType.FETCH_FROM_CHEST
                    && !decision.parameters().has("item_id")) {
                logger.info("Fetch decision missing parameters.item_id for agent {}. Retrying.", handle.npcName());
                nextThinkAt.put(npcId, now + 1200L);
                continue;
            }
            if (decision.intent() == AgentIntentType.MINE_BLOCK
                    && !decision.parameters().has("block")
                    && !(decision.parameters().has("x") && decision.parameters().has("y") && decision.parameters().has("z"))) {
                logger.info("Mine decision missing block target for agent {}. Retrying.", handle.npcName());
                nextThinkAt.put(npcId, now + 1200L);
                continue;
            }
            if (decision.intent() == AgentIntentType.MINE_TO_CHEST
                    && !decision.parameters().has("block")) {
                logger.info("Mine-to-chest decision missing parameters.block for agent {}. Retrying.", handle.npcName());
                nextThinkAt.put(npcId, now + 1200L);
                continue;
            }
            if (decision.intent() == AgentIntentType.MINE_TO_PLAYER
                    && !decision.parameters().has("block")) {
                logger.info("Mine-to-player decision missing parameters.block for agent {}. Retrying.", handle.npcName());
                nextThinkAt.put(npcId, now + 1200L);
                continue;
            }
            if (demand == InstructionDemand.REQUIRE_MINE && decision.intent() != AgentIntentType.MINE_BLOCK) {
                logger.info("Mine instruction requires MINE_BLOCK intent for agent {}. Got {}. Retrying.",
                        handle.npcName(), decision.intent());
                nextThinkAt.put(npcId, now + 1200L);
                continue;
            }
            if (demand == InstructionDemand.REQUIRE_MINE_TO_CHEST && decision.intent() != AgentIntentType.MINE_TO_CHEST) {
                logger.info("Mine-to-chest instruction requires MINE_TO_CHEST intent for agent {}. Got {}. Retrying.",
                        handle.npcName(), decision.intent());
                nextThinkAt.put(npcId, now + 1200L);
                continue;
            }
            if (demand == InstructionDemand.REQUIRE_MINE_TO_PLAYER && decision.intent() != AgentIntentType.MINE_TO_PLAYER) {
                logger.info("Mine-to-player instruction requires MINE_TO_PLAYER intent for agent {}. Got {}. Retrying.",
                        handle.npcName(), decision.intent());
                nextThinkAt.put(npcId, now + 1200L);
                continue;
            }
            if (demand == InstructionDemand.REQUIRE_FETCH && decision.intent() != AgentIntentType.FETCH_FROM_CHEST) {
                logger.info("Fetch instruction requires FETCH_FROM_CHEST intent for agent {}. Got {}. Retrying.",
                        handle.npcName(), decision.intent());
                nextThinkAt.put(npcId, now + 1200L);
                continue;
            }

            actionExecutor.execute(npcId, decision);
            logger.info(
                    "LLM decision queued: agent={} intent={} priority={} reasoning={}",
                    handle.npcName(),
                    decision.intent(),
                    decision.priority(),
                    decision.reasoning()
            );
            memoryStore.appendWorking(npcId, MemoryEntry.working(decision, true));
            memoryStore.appendLongTerm(npcId, MemoryEntry.episodic("decision", snapshot.toJson().toString()));
            pendingInstruction.put(npcId, false);
            nextThinkAt.put(npcId, now + 1500L);
        }
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
        lines.add("last_demand=" + lastDemandByNpc.getOrDefault(npcId, "none"));
        lines.add("expected_intent=" + lastExpectedIntentByNpc.getOrDefault(npcId, "none"));
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

    private InstructionDemand classifyDemand(String instruction) {
        String lower = instruction == null ? "" : instruction.toLowerCase(Locale.ROOT);
        if ((lower.contains("mine") || lower.contains("dig") || lower.contains("break"))
                && (lower.contains("give me")
                || lower.contains("give to me")
                || lower.contains("give it to me")
                || lower.contains("give them to me")
                || lower.contains("deliver to me")
                || lower.contains("to my inventory")
                || lower.contains("my inventory")
                || mentionsInventory(lower))) {
            return InstructionDemand.REQUIRE_MINE_TO_PLAYER;
        }
        if ((lower.contains("mine") || lower.contains("dig") || lower.contains("break"))
                && (lower.contains("chest") || lower.contains("store") || lower.contains("put in"))) {
            return InstructionDemand.REQUIRE_MINE_TO_CHEST;
        }
        if (lower.contains("mine") || lower.contains("dig") || lower.contains("break")) {
            return InstructionDemand.REQUIRE_MINE;
        }
        if (lower.contains("buy")
                || lower.contains("trade")
                || lower.contains("deal")
                || lower.contains("emerald")
                || lower.contains("counter")
                || lower.contains("no thanks")
                || lower.contains("decline")) {
            return InstructionDemand.REQUIRE_TRADE;
        }
        if (lower.contains("bring") || lower.contains("fetch") || lower.contains("get me")) {
            return InstructionDemand.REQUIRE_FETCH;
        }
        return InstructionDemand.NONE;
    }

    private boolean mentionsInventory(String lower) {
        if (lower == null || lower.isBlank()) {
            return false;
        }
        return lower.contains("inventory")
                || lower.contains("inverntory")
                || lower.matches(".*\\binv\\w*tory\\b.*");
    }

    private String expectedIntentForDemand(InstructionDemand demand) {
        return switch (demand) {
            case REQUIRE_MINE -> "mine_block";
            case REQUIRE_FETCH -> "fetch_from_chest";
            case REQUIRE_MINE_TO_CHEST -> "mine_to_chest";
            case REQUIRE_MINE_TO_PLAYER -> "mine_to_player";
            case REQUIRE_TRADE -> "dialogue_reply";
            case NONE -> "";
        };
    }

    private String targetHintFromInstruction(String instruction, InstructionDemand demand) {
        String lower = instruction == null ? "" : instruction.toLowerCase(Locale.ROOT);
        if (lower.contains("grass")) {
            return demand == InstructionDemand.REQUIRE_FETCH ? "minecraft:grass_block" : "minecraft:grass_block";
        }
        if (lower.contains("glass")) {
            return demand == InstructionDemand.REQUIRE_FETCH ? "minecraft:glass" : "minecraft:glass";
        }
        if (lower.contains("cobblestone")) {
            return demand == InstructionDemand.REQUIRE_FETCH ? "minecraft:cobblestone" : "minecraft:cobblestone";
        }
        if (lower.contains("oak log")) {
            return demand == InstructionDemand.REQUIRE_FETCH ? "minecraft:oak_log" : "minecraft:oak_log";
        }
        return "";
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
            logger.info("Restored embodied AI agent {} from saved binding", npcName);
        }
    }

    private record AgentHandle(UUID npcId, String npcName, UUID ownerPlayerId) {
    }

    private enum InstructionDemand {
        NONE,
        REQUIRE_MINE,
        REQUIRE_MINE_TO_CHEST,
        REQUIRE_MINE_TO_PLAYER,
        REQUIRE_TRADE,
        REQUIRE_FETCH
    }
}
