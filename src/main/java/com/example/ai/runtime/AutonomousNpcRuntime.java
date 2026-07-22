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
    private final Map<UUID, PendingClarification> pendingClarificationByNpc = new ConcurrentHashMap<>();
    private final Map<UUID, PendingConfirmation> pendingConfirmationByNpc = new ConcurrentHashMap<>();

    private record PendingClarification(String originalInstruction, String question, long expiresAtMillis) {
    }

    private record PendingConfirmation(AgentDecision decision, String instruction, String speaker,
                                       String description, long expiresAtMillis) {
    }

    private final ExecutorService llmExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<UUID> thinkingNpcs = ConcurrentHashMap.newKeySet();
    private com.example.ai.test.TestRunner activeTestRunner = null;
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

            actionExecutor.suppressVanillaBrain(server, npcId);
            actionExecutor.enforceOwnerLeash(server, npcId, handle.ownerPlayerId(), handle.npcName());
            actionExecutor.lookAtOwnerIfIdle(server, npcId, handle.ownerPlayerId());
            actionExecutor.applyNextAction(server, npcId, handle.npcName());

            List<String> summaries = actionExecutor.pollCompletedTaskSummaries(npcId);
            if (summaries != null) {
                for (String summary : summaries) {
                    memoryStore.appendLongTerm(npcId, handle.ownerPlayerId(), MemoryEntry.episodic("task_result", summary));
                    memoryStore.appendShortTerm(npcId, MemoryEntry.dialogue(handle.npcName(), summary));
                }
            }
            List<String> utterances = actionExecutor.pollNpcUtterances(npcId);
            if (utterances != null) {
                for (String utt : utterances) {
                    memoryStore.appendShortTerm(npcId, MemoryEntry.dialogue(handle.npcName(), utt));
                }
            }

            if (thinkingNpcs.contains(npcId)) continue;

            // Fire welcome-back greeting once after server restart, before normal processing
            if (pendingWelcomeBack.contains(npcId)) {
                MemoryContext greetMem = memoryStore.getContext(npcId, handle.ownerPlayerId());
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

            MemoryContext memory       = memoryStore.getContext(npcId, handle.ownerPlayerId());
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
            actionExecutor.notifyThinking(server, npcId, handle.npcName());
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

        if (activeTestRunner != null) {
            activeTestRunner.tick();
            if (activeTestRunner.isFinished()) {
                activeTestRunner = null;
            }
        }
    }

    public void resetNpcForTest(UUID npcId) {
        UUID playerId = agents.containsKey(npcId) ? agents.get(npcId).ownerPlayerId() : null;
        actionExecutor.clearActiveTradeOffer(npcId, playerId);
        actionExecutor.cancelAllActiveTasks(npcId);
        pendingConfirmationByNpc.remove(npcId);
        pendingClarificationByNpc.remove(npcId);
        lastProcessedInstructionByNpc.remove(npcId);
    }

    public boolean startTestRun(UUID npcId, net.minecraft.server.level.ServerPlayer requester) {
        if (activeTestRunner != null && !activeTestRunner.isFinished()) {
            return false;
        }
        activeTestRunner = new com.example.ai.test.TestRunner(
                logger,
                npcId,
                requester,
                () -> getLastParsedIntent(npcId),
                () -> isNpcIdle(npcId),
                instruction -> enqueuePlayerUtterance(npcId, requester.getName().getString(), instruction),
                () -> resetNpcForTest(npcId)
        );
        return true;
    }

    private void processInstruction(MinecraftServer server, AgentHandle handle, UUID npcId,
                                    MemoryContext memory, WorldSnapshot snapshot,
                                    String speaker, String latestInstruction,
                                    long now, boolean hasPendingInstruction) {
        logger.info("[THINK] npc={} instruction='{}' speaker='{}'",
                handle.npcName(), latestInstruction, speaker);
        String previousInstruction = lastProcessedInstructionByNpc.getOrDefault(npcId, "");
        if (previousInstruction.equalsIgnoreCase(latestInstruction)) {
            previousInstruction = "";
        }
        runActionSelection(server, handle, npcId, memory, snapshot, speaker, latestInstruction,
                previousInstruction, now, hasPendingInstruction);
    }

    private void runActionSelection(MinecraftServer server, AgentHandle handle, UUID npcId,
                                    MemoryContext memory, WorldSnapshot snapshot,
                                    String speaker, String latestInstruction,
                                    String previousInstruction,
                                    long now, boolean hasPendingInstruction) {
        String activeOffer = actionExecutor.activeOfferSummary(npcId, handle.ownerPlayerId());
        PendingClarification pendingClarification = pendingClarificationByNpc.get(npcId);
        if (pendingClarification != null && System.currentTimeMillis() > pendingClarification.expiresAtMillis()) {
            pendingClarificationByNpc.remove(npcId);
            pendingClarification = null;
        }
        String pendingClarificationSummary = pendingClarification == null ? ""
                : "original_instruction='" + pendingClarification.originalInstruction()
                        + "'; npc_question='" + pendingClarification.question() + "'";
        PendingConfirmation pendingConfirmation = pendingConfirmationByNpc.get(npcId);
        if (pendingConfirmation != null && System.currentTimeMillis() > pendingConfirmation.expiresAtMillis()) {
            pendingConfirmationByNpc.remove(npcId);
            pendingConfirmation = null;
        }
        String pendingConfirmationSummary = pendingConfirmation == null ? ""
                : "proposed_action='" + pendingConfirmation.description() + "'";
        String prompt = promptFactory.actionSelectionPrompt(
                snapshot, memory, hasPendingInstruction, latestInstruction, "", "", previousInstruction, activeOffer,
                pendingClarificationSummary, pendingConfirmationSummary);

        llmInFlight.put(npcId, true);
        String raw;
        try {
            raw = llmRouter.generate(prompt);
        } finally {
            llmInFlight.put(npcId, false);
        }

        AgentDecision decision = decisionParser.parse(raw);
        lastParsedDecisionIntentByNpc.put(npcId, decision.intent().name().toLowerCase());
        if (decision.intent() != AgentIntentType.ASK_CLARIFICATION
                && decision.intent() != AgentIntentType.DIALOGUE_REPLY
                && decision.intent() != AgentIntentType.IDLE) {
            pendingClarificationByNpc.remove(npcId);
        }

        if (decision.intent() == AgentIntentType.CONFIRM_YES) {
            pendingConfirmationByNpc.remove(npcId);
            if (pendingConfirmation != null) {
                executeConfirmedAction(server, handle, npcId, pendingConfirmation);
            } else {
                actionExecutor.sayAsNpc(server, npcId, handle.npcName(), "There's nothing waiting for me to confirm.");
                pendingInstruction.put(npcId, false);
                nextThinkAt.put(npcId, System.currentTimeMillis() + 800L);
            }
            return;
        }
        if (decision.intent() == AgentIntentType.CONFIRM_NO) {
            pendingConfirmationByNpc.remove(npcId);
            actionExecutor.sayAsNpc(server, npcId, handle.npcName(), "Alright, I won't do that.");
            pendingInstruction.put(npcId, false);
            nextThinkAt.put(npcId, System.currentTimeMillis() + 800L);
            storeActionMemory(npcId, speaker, latestInstruction, "dialogue");
            lastProcessedInstructionByNpc.put(npcId, latestInstruction);
            return;
        }
        // A new decision that is not a confirmation reply supersedes any pending confirmation.
        pendingConfirmationByNpc.remove(npcId);

        if (decision.intent() == AgentIntentType.CANCEL_TASK) {
            boolean cancelled = actionExecutor.cancelActiveTasks(server, npcId, handle.npcName());
            if (!cancelled) {
                actionExecutor.sayAsNpc(server, npcId, handle.npcName(),
                        "I'm not in the middle of anything right now.");
            }
            pendingInstruction.put(npcId, false);
            nextThinkAt.put(npcId, System.currentTimeMillis() + 800L);
            storeActionMemory(npcId, speaker, latestInstruction, "cancel");
            lastProcessedInstructionByNpc.put(npcId, latestInstruction);
            return;
        }

        // When NPC is busy, trade and explicit new build requests execute; everything else becomes dialogue
        if (actionExecutor.getNpcState(npcId) != AgentActionExecutor.NpcState.IDLE
                && !decision.intent().name().startsWith("TRADE_")
                && decision.intent() != AgentIntentType.BUILD_STRUCTURE) {
            if (actionExecutor.tryHandleDialogueInstruction(
                    server, npcId, handle.npcName(), speaker, handle.ownerPlayerId(),
                    latestInstruction, memory, snapshot)) {
                pendingInstruction.put(npcId, false);
                nextThinkAt.put(npcId, System.currentTimeMillis() + 800L);
                storeActionMemory(npcId, speaker, latestInstruction, "dialogue");
            } else {
                pendingInstruction.put(npcId, false);
                nextThinkAt.put(npcId, System.currentTimeMillis() + 1200L);
            }
            return;
        }

        // Route intents that have dedicated handlers directly — no extra classifier call needed
        if (decision.intent() == AgentIntentType.RECIPE_REPLY) {
            if (actionExecutor.tryHandleRecipeInstructionDirect(
                    server, npcId, handle.npcName(), speaker, handle.ownerPlayerId(),
                    latestInstruction, memory, snapshot)) {
                pendingInstruction.put(npcId, false);
                nextThinkAt.put(npcId, System.currentTimeMillis() + 800L);
                storeActionMemory(npcId, speaker, latestInstruction, "recipe");
            } else {
                pendingInstruction.put(npcId, false);
                nextThinkAt.put(npcId, System.currentTimeMillis() + 1200L);
            }
            return;
        }
        if (decision.intent() == AgentIntentType.SEARCH_ENTITY
                || decision.intent() == AgentIntentType.SCOUT_EXPLORER) {
            requestConfirmation(server, handle, npcId, decision, latestInstruction, speaker);
            return;
        }

        if (!decision.isValid()) {
            logger.info("LLM produced invalid decision for agent {}: {}", handle.npcName(), raw);
            nextThinkAt.put(npcId, System.currentTimeMillis() + 1200L);
            return;
        }
        if (!safetyPolicy.allow(npcId, decision)) {
            logger.info("Safety policy blocked decision for agent {}: {}", handle.npcName(), decision.intent());
            nextThinkAt.put(npcId, System.currentTimeMillis() + 1200L);
            return;
        }
        if (decision.intent() == AgentIntentType.IDLE) {
            boolean llmUnavailable = decision.reasoning() != null
                    && decision.reasoning().toLowerCase().contains("unavailable");
            if (llmUnavailable) {
                logger.info("LLM unavailable for agent {}, backing off 5s.", handle.npcName());
                actionExecutor.notifyLlmUnavailable(server, npcId, handle.npcName());
                pendingInstruction.put(npcId, false);
                nextThinkAt.put(npcId, System.currentTimeMillis() + 5000L);
            } else {
                logger.info("Idle decision rejected for pending instruction on agent {}. Retrying.", handle.npcName());
                nextThinkAt.put(npcId, System.currentTimeMillis() + 1200L);
            }
            return;
        }
        if (decision.intent() == AgentIntentType.ASK_CLARIFICATION) {
            String question = decision.parameters().has("text")
                    ? decision.parameters().get("text").getAsString()
                    : "";
            if (question.isBlank()) {
                question = "I'm not sure what you mean — could you say that another way?";
            }
            PendingClarification existing = pendingClarificationByNpc.get(npcId);
            String original = existing != null ? existing.originalInstruction() : latestInstruction;
            pendingClarificationByNpc.put(npcId,
                    new PendingClarification(original, question, System.currentTimeMillis() + 90_000L));
            actionExecutor.sayAsNpc(server, npcId, handle.npcName(), question);
            pendingInstruction.put(npcId, false);
            nextThinkAt.put(npcId, System.currentTimeMillis() + 800L);
            storeActionMemory(npcId, speaker, latestInstruction, "clarification");
            lastProcessedInstructionByNpc.put(npcId, latestInstruction);
            return;
        }
        if (decision.intent() == AgentIntentType.DIALOGUE_REPLY && !decision.parameters().has("text")) {
            logger.info("Dialogue decision missing parameters.text for agent {}. Retrying.", handle.npcName());
            nextThinkAt.put(npcId, System.currentTimeMillis() + 1200L);
            return;
        }
        if (decision.intent() == AgentIntentType.FETCH_FROM_CHEST && !decision.parameters().has("item_id")) {
            logger.info("Fetch decision missing parameters.item_id for agent {}. Retrying.", handle.npcName());
            nextThinkAt.put(npcId, System.currentTimeMillis() + 1200L);
            return;
        }
        if (decision.intent() == AgentIntentType.MINE_BLOCK) {
            String mineScope = decision.parameters().has("scope")
                    ? decision.parameters().get("scope").getAsString().toLowerCase() : "single";
            boolean scopeTargetsSelf = mineScope.equals("tree") || mineScope.equals("own_build");
            boolean hasBlockTarget = decision.parameters().has("block")
                    || (decision.parameters().has("x") && decision.parameters().has("y") && decision.parameters().has("z"));
            if (!scopeTargetsSelf && !hasBlockTarget) {
                logger.info("Mine decision missing block target for agent {}. Retrying.", handle.npcName());
                nextThinkAt.put(npcId, System.currentTimeMillis() + 1200L);
                return;
            }
            if (mineScope.equals("tree") || mineScope.equals("area") || mineScope.equals("own_build")) {
                requestConfirmation(server, handle, npcId, decision, latestInstruction, speaker);
                return;
            }
        }
        if (decision.intent() == AgentIntentType.MINE_TO_CHEST && !decision.parameters().has("block")) {
            logger.info("Mine-to-chest decision missing parameters.block for agent {}. Retrying.", handle.npcName());
            nextThinkAt.put(npcId, System.currentTimeMillis() + 1200L);
            return;
        }
        if (decision.intent() == AgentIntentType.MINE_TO_PLAYER && !decision.parameters().has("block")) {
            logger.info("Mine-to-player decision missing parameters.block for agent {}. Retrying.", handle.npcName());
            nextThinkAt.put(npcId, System.currentTimeMillis() + 1200L);
            return;
        }

        if (decision.intent() == AgentIntentType.BUILD_STRUCTURE) {
            String buildInstruction = decision.parameters().has("description")
                    ? decision.parameters().get("description").getAsString()
                    : "";
            if (buildInstruction.isBlank()) {
                buildInstruction = latestInstruction;
            }
            if (actionExecutor.tryHandleScenarioInstruction(
                    server, npcId, handle.npcName(), speaker, handle.ownerPlayerId(),
                    buildInstruction, memory, snapshot, true)) {
                pendingInstruction.put(npcId, false);
                nextThinkAt.put(npcId, System.currentTimeMillis() + 800L);
                storeActionMemory(npcId, speaker, latestInstruction, "scenario");
                return;
            }
        }
        if (decision.intent() == AgentIntentType.DIALOGUE_REPLY) {
            if (actionExecutor.tryHandleDialogueInstruction(
                    server, npcId, handle.npcName(), speaker, handle.ownerPlayerId(),
                    latestInstruction, memory, snapshot)) {
                pendingInstruction.put(npcId, false);
                nextThinkAt.put(npcId, System.currentTimeMillis() + 800L);
                memoryStore.appendLongTerm(npcId, handle.ownerPlayerId(), MemoryEntry.episodic("dialogue",
                        "Handled dialogue from " + speaker + ": " + latestInstruction));
                lastProcessedInstructionByNpc.put(npcId, latestInstruction);
                return;
            }
        }
        if (decision.intent().name().startsWith("TRADE_")) {
            if (actionExecutor.tryHandleTradeInstruction(
                    server, npcId, handle.npcName(), speaker, handle.ownerPlayerId(),
                    latestInstruction, memory, snapshot, decision.intent())) {
                pendingInstruction.put(npcId, false);
                nextThinkAt.put(npcId, System.currentTimeMillis() + 800L);
                memoryStore.appendLongTerm(npcId, handle.ownerPlayerId(), MemoryEntry.episodic("trade",
                        "Handled trade instruction from " + speaker + ": " + latestInstruction));
                lastProcessedInstructionByNpc.put(npcId, latestInstruction);
                return;
            }
            pendingInstruction.put(npcId, false);
            nextThinkAt.put(npcId, System.currentTimeMillis() + 1200L);
            return;
        }

        actionExecutor.execute(npcId, decision);
        logger.info("LLM decision queued: agent={} intent={} priority={} reasoning={}",
                handle.npcName(), decision.intent(), decision.priority(), decision.reasoning());
        memoryStore.appendWorking(npcId, MemoryEntry.working(decision, true));
        memoryStore.appendLongTerm(npcId, handle.ownerPlayerId(), MemoryEntry.episodic("decision",
                "Executed " + decision.intent().name().toLowerCase() + ": " + decision.reasoning()));
        pendingInstruction.put(npcId, false);
        nextThinkAt.put(npcId, System.currentTimeMillis() + 1500L);
        lastProcessedInstructionByNpc.put(npcId, latestInstruction);
    }

    public void onPlayerJoin(UUID playerId) {
        for (AgentHandle handle : agents.values()) {
            if (playerId.equals(handle.ownerPlayerId())) {
                pendingWelcomeBack.add(handle.npcId());
            }
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

    public boolean isNpcIdle(UUID npcId) {
        return !pendingInstruction.getOrDefault(npcId, false)
                && !llmInFlight.getOrDefault(npcId, false)
                && !thinkingNpcs.contains(npcId);
    }

    public String getLastParsedIntent(UUID npcId) {
        return lastParsedDecisionIntentByNpc.get(npcId);
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
        MemoryContext context = memoryStore.getContext(npcId, handle.ownerPlayerId());

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

            ((Villager) entity).getBrain().removeAllBehaviors();
            agents.put(npcId, new AgentHandle(npcId, npcName, ownerPlayerId));
            pendingInstruction.put(npcId, false);
            pendingWelcomeBack.add(npcId);
            logger.info("Restored embodied AI agent {} from saved binding", npcName);
        }
    }

    private void requestConfirmation(MinecraftServer server, AgentHandle handle, UUID npcId,
                                     AgentDecision decision, String instruction, String speaker) {
        String description = describeProposedAction(decision);
        pendingConfirmationByNpc.put(npcId,
                new PendingConfirmation(decision, instruction, speaker, description,
                        System.currentTimeMillis() + 90_000L));
        actionExecutor.sayAsNpc(server, npcId, handle.npcName(), "Do you want me to " + description + "?");
        pendingInstruction.put(npcId, false);
        nextThinkAt.put(npcId, System.currentTimeMillis() + 800L);
    }

    private String describeProposedAction(AgentDecision decision) {
        if (decision.intent() == AgentIntentType.SEARCH_ENTITY) {
            String label = decision.parameters().has("entity_label")
                    ? decision.parameters().get("entity_label").getAsString()
                    : (decision.parameters().has("entity_id")
                            ? decision.parameters().get("entity_id").getAsString() : "that");
            return "search for " + label;
        }
        if (decision.intent() == AgentIntentType.SCOUT_EXPLORER) {
            String direction = decision.parameters().has("direction")
                    ? decision.parameters().get("direction").getAsString() : "around";
            return "scout " + direction + " and report back";
        }
        if (decision.intent() == AgentIntentType.MINE_BLOCK) {
            String mineScope = decision.parameters().has("scope")
                    ? decision.parameters().get("scope").getAsString().toLowerCase() : "single";
            return switch (mineScope) {
                case "tree" -> "cut down the nearest tree";
                case "own_build" -> "take down the structure I built";
                case "area" -> "clear the " + (decision.parameters().has("block")
                        ? decision.parameters().get("block").getAsString().replace("minecraft:", "") : "blocks")
                        + " nearby";
                default -> "mine that";
            };
        }
        return "do that";
    }

    private void executeConfirmedAction(MinecraftServer server, AgentHandle handle, UUID npcId,
                                        PendingConfirmation confirmation) {
        AgentDecision decision = confirmation.decision();
        if (decision.intent() == AgentIntentType.SEARCH_ENTITY) {
            doSearch(server, handle, npcId, decision, confirmation.instruction(), confirmation.speaker());
        } else if (decision.intent() == AgentIntentType.SCOUT_EXPLORER) {
            doScout(server, handle, npcId, decision, confirmation.instruction(), confirmation.speaker());
        } else if (decision.intent() == AgentIntentType.MINE_BLOCK) {
            actionExecutor.execute(npcId, decision);
            memoryStore.appendWorking(npcId, MemoryEntry.working(decision, true));
            memoryStore.appendLongTerm(npcId, handle.ownerPlayerId(), MemoryEntry.episodic("decision",
                    "Executed " + decision.intent().name().toLowerCase() + ": " + decision.reasoning()));
            pendingInstruction.put(npcId, false);
            nextThinkAt.put(npcId, System.currentTimeMillis() + 1500L);
            lastProcessedInstructionByNpc.put(npcId, confirmation.instruction());
        } else {
            pendingInstruction.put(npcId, false);
            nextThinkAt.put(npcId, System.currentTimeMillis() + 800L);
        }
    }

    private void doSearch(MinecraftServer server, AgentHandle handle, UUID npcId,
                          AgentDecision decision, String instruction, String speaker) {
        String entityId = decision.parameters().has("entity_id")
                ? decision.parameters().get("entity_id").getAsString() : "";
        String entityLabel = decision.parameters().has("entity_label")
                ? decision.parameters().get("entity_label").getAsString() : entityId;
        if (actionExecutor.executeSearchDirect(
                server, npcId, handle.npcName(), speaker, handle.ownerPlayerId(),
                entityId, entityLabel)) {
            pendingInstruction.put(npcId, false);
            nextThinkAt.put(npcId, System.currentTimeMillis() + 800L);
            storeActionMemory(npcId, speaker, instruction, "search");
        } else {
            pendingInstruction.put(npcId, false);
            nextThinkAt.put(npcId, System.currentTimeMillis() + 1200L);
        }
    }

    private void doScout(MinecraftServer server, AgentHandle handle, UUID npcId,
                         AgentDecision decision, String instruction, String speaker) {
        if (actionExecutor.executeScoutDirect(
                server, npcId, handle.npcName(), speaker, handle.ownerPlayerId(),
                instruction, decision.parameters())) {
            pendingInstruction.put(npcId, false);
            nextThinkAt.put(npcId, System.currentTimeMillis() + 800L);
            storeActionMemory(npcId, speaker, instruction, "scout");
        } else {
            pendingInstruction.put(npcId, false);
            nextThinkAt.put(npcId, System.currentTimeMillis() + 1200L);
        }
    }

    private void storeActionMemory(UUID npcId, String speaker, String instruction, String type) {
        UUID playerId = agents.containsKey(npcId) ? agents.get(npcId).ownerPlayerId() : null;
        String summary = actionExecutor.lastActionSummary(npcId);
        String entry = summary.isBlank()
                ? speaker + " asked me to " + type + ": " + instruction
                : summary + " (requested by " + speaker + ")";
        memoryStore.appendWorking(npcId, MemoryEntry.episodic(type, entry));
        memoryStore.appendLongTerm(npcId, playerId, MemoryEntry.episodic(type, entry));
        lastProcessedInstructionByNpc.put(npcId, instruction);
    }

    private record AgentHandle(UUID npcId, String npcName, UUID ownerPlayerId) {
    }

}
