package com.example.ai.action;

import com.example.ai.intent.AgentDecision;
import com.example.ai.llm.LlmRouter;
import com.example.ai.memory.MemoryContext;
import com.example.ai.perception.SemanticPerceptionService;
import com.example.ai.perception.WorldSnapshot;
import com.example.ai.prompt.PromptFactory;
import com.example.ai.scenario.ScenarioPlan;
import com.example.ai.scenario.ScenarioPlanParser;
import com.example.ai.scenario.ScenarioStep;
import com.example.ai.trade.ParsedTradeIntent;
import com.example.ai.trade.TradeNegotiationDraft;
import com.example.ai.trade.TradeNegotiationParser;
import com.example.ai.trade.TradeCounterResult;
import com.example.ai.trade.TradeIntentClassifierDraft;
import com.example.ai.trade.TradeIntentClassifierParser;
import com.example.ai.trade.TradeIntentType;
import com.example.ai.trade.PriceConfig;
import com.example.ai.trade.TradeOffer;
import com.example.ai.trade.TradeOfferEngine;
import com.example.ai.trade.TradePriceStore;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

public final class AgentActionExecutor {
    private static final int CHEST_SCAN_RADIUS = 16;
    private static final int MINE_SCAN_RADIUS = 8;
    private static final int TRADE_SCAN_RADIUS = 48;
    private static final int SEARCH_BASE_RADIUS = 24;
    private static final int SEARCH_MAX_RADIUS = 96;
    private static final int SEARCH_BEACON_TICKS = 20 * 30;
    private static final int SEARCH_SCAN_INTERVAL_TICKS = 40;
    private static final int SEARCH_PATROL_STALL_TICKS = 40;
    private static final long SEARCH_STATUS_COOLDOWN_MILLIS = 6_000L;
    private static final long SEARCH_TRADE_SUPPRESS_MILLIS = 60_000L;
    private static final int SCOUT_BASE_DISTANCE = 48;
    private static final int SCOUT_MAX_DISTANCE = 96;
    private static final int SCOUT_SURVEY_TICKS = 20;
    private static final long SCOUT_STATUS_COOLDOWN_MILLIS = 6_000L;
    private static final double TRADE_INTENT_CONFIDENCE_THRESHOLD = 0.6;
    private static final boolean TRADE_DEBUG_CHAT = true;
    private static final long TRADE_GREETING_COOLDOWN_MILLIS = 20_000L;
    private static final long TRADE_RECENT_INTERACTION_SUPPRESS_MILLIS = 45_000L;
    private static final double OWNER_IDLE_RADIUS_SQR = 36.0; // 6 blocks
    private static final double OWNER_PULL_RADIUS_SQR = 196.0; // 14 blocks
    private static final double OWNER_TELEPORT_RADIUS_SQR = 3600.0; // 60 blocks
    private static final double TRADE_GREETING_RADIUS_SQR = 25.0; // 5 blocks

    private final Logger logger;
    private final PromptFactory promptFactory;
    private final LlmRouter llmRouter;
    private final SemanticPerceptionService perceptionService = new SemanticPerceptionService();
    private final TradeOfferEngine tradeOfferEngine = new TradeOfferEngine(TradePriceStore.load());
    private final TradeIntentClassifierParser tradeIntentClassifierParser = new TradeIntentClassifierParser();
    private final TradeNegotiationParser tradeNegotiationParser = new TradeNegotiationParser();
    private final Map<UUID, List<JsonObject>> actionOutbox = new ConcurrentHashMap<>();
    private final Map<UUID, List<String>> completedTaskSummaries = new ConcurrentHashMap<>();
    private final Map<UUID, DeliveryTask> activeDeliveries = new ConcurrentHashMap<>();
    private final Map<UUID, MineToChestTask> activeMineToChest = new ConcurrentHashMap<>();
    private final Map<UUID, MineToPlayerTask> activeMineToPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, SearchTask> activeSearchTasks = new ConcurrentHashMap<>();
    private final Map<UUID, ScoutTask> activeScoutTasks = new ConcurrentHashMap<>();
    private final Map<UUID, ScenarioTask> activeScenarioTasks = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastActionSummaryByNpc = new ConcurrentHashMap<>();
    private final ScenarioPlanParser scenarioPlanParser = new ScenarioPlanParser();
    private final Map<UUID, String> lastTradeIntentByNpc = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> tradeLlmInFlightByNpc = new ConcurrentHashMap<>();
    private final Map<TradeSessionKey, TradeSession> tradeSessions = new ConcurrentHashMap<>();
    private final Map<TradeSessionKey, Long> nextTradeGreetingAt = new ConcurrentHashMap<>();

    private static final long ENV_ADVISORY_COOLDOWN_MILLIS = 120_000L; 
    private final Map<UUID, Long> nextEnvironmentalAdvisoryAt = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> environmentalLlmInFlightByNpc = new ConcurrentHashMap<>();


    private final Map<UUID, String> lastEnvironmentalSignature = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastEnvironmentalThreatLevel = new ConcurrentHashMap<>();

    public AgentActionExecutor(Logger logger, PromptFactory promptFactory, LlmRouter llmRouter) {
        this.logger = logger;
        this.promptFactory = promptFactory;
        this.llmRouter = llmRouter;
    }

    public void updateTradePrices(Map<String, PriceConfig> configs) {
        tradeOfferEngine.updatePrices(configs);
    }

    public Map<String, PriceConfig> currentTradePriceConfigs() {
        return tradeOfferEngine.currentConfigs();
    }

    public boolean execute(UUID npcId, AgentDecision decision) {
        JsonObject action = new JsonObject();
        action.addProperty("intent", decision.intent().name().toLowerCase());
        action.add("parameters", decision.parameters());
        action.addProperty("priority", decision.priority());
        action.addProperty("reasoning", decision.reasoning());

        actionOutbox.compute(npcId, (id, list) -> {
            List<JsonObject> safe = list == null ? new ArrayList<>() : new ArrayList<>(list);
            safe.add(action);
            if (safe.size() > 64) {
                safe.remove(0);
            }
            return safe;
        });
        logger.debug("Queued action for {}: {}", npcId, action);
        return true;
    }

    public WorldSnapshot captureWorldSnapshot(MinecraftServer server, UUID npcId, String fallbackName) {
        Villager villager = findVillager(server, npcId);
        if (villager == null || !(villager.level() instanceof ServerLevel level)) {
            return perceptionService.captureFallback(fallbackName);
        }

        BlockPos center = villager.blockPosition();
        Map<String, Integer> nearbyBlocks = scanNearbyBlocks(level, center, 8);
        List<WorldSnapshot.SeenEntity> nearbyEntities = scanNearbyEntities(level, villager, 16);
        WorldSnapshot.EnvironmentContext environment = buildEnvironmentContext(level, center, nearbyEntities);
        return perceptionService.capture(
                fallbackName,
                new WorldSnapshot.Position(center.getX(), center.getY(), center.getZ()),
                nearbyBlocks,
                nearbyEntities,
                environment);
    }

    private Map<String, Integer> scanNearbyBlocks(ServerLevel level, BlockPos center, int radius) {
        Map<String, Integer> histogram = new LinkedHashMap<>();
        int radiusSqr = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (center.distSqr(pos) > radiusSqr) {
                        continue;
                    }
                    var state = level.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }
                    String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                    histogram.merge(blockId, 1, Integer::sum);
                }
            }
        }
        return histogram;
    }

    private List<WorldSnapshot.SeenEntity> scanNearbyEntities(ServerLevel level, Villager villager, int radius) {
        List<WorldSnapshot.SeenEntity> entities = new ArrayList<>();
        for (Entity entity : level.getEntities(villager, villager.getBoundingBox().inflate(radius))) {
            if (entity == villager) {
                continue;
            }
            double distance = entity.distanceTo(villager);
            String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
            String name = entity.getDisplayName().getString();
            entities.add(new WorldSnapshot.SeenEntity(type, name, distance));
        }
        entities.sort(Comparator.comparingDouble(WorldSnapshot.SeenEntity::distance));
        if (entities.size() > 25) {
            return new ArrayList<>(entities.subList(0, 25));
        }
        return entities;
    }

    private WorldSnapshot.EnvironmentContext buildEnvironmentContext(ServerLevel level, BlockPos center,
            List<WorldSnapshot.SeenEntity> nearbyEntities) {
        String biome = level.getBiome(center)
                .unwrapKey()
                .map(Object::toString)
                .orElse(level.dimension().toString());
        long timeOfDay = level.getOverworldClockTime() % 24000L;
        boolean isNight = !level.isBrightOutside();
        String weather;
        if (level.isThundering()) {
            weather = "thunder";
        } else if (level.isRaining()) {
            weather = "rain";
        } else {
            weather = "clear";
        }
        int hostileCount = 0;
        for (WorldSnapshot.SeenEntity entity : nearbyEntities) {
            if (isLikelyHostile(entity.type())) {
                hostileCount += 1;
            }
        }
        int light = level.getMaxLocalRawBrightness(center);
        String threatLevel;
        if (hostileCount >= 2 || (hostileCount >= 1 && (isNight || light < 7))) {
            threatLevel = "high";
        } else if (hostileCount >= 1 || light < 7 || isNight) {
            threatLevel = "medium";
        } else {
            threatLevel = "low";
        }
        return new WorldSnapshot.EnvironmentContext(biome, timeOfDay, isNight, weather, threatLevel);
    }

    private boolean isLikelyHostile(String entityType) {
        String lower = entityType.toLowerCase(Locale.ROOT);
        return lower.contains("creeper")
                || lower.contains("zombie")
                || lower.contains("skeleton")
                || lower.contains("spider")
                || lower.contains("witch")
                || lower.contains("slime")
                || lower.contains("enderman")
                || lower.contains("blaze")
                || lower.contains("drowned")
                || lower.contains("warden")
                || lower.contains("pillager")
                || lower.contains("vindicator")
                || lower.contains("phantom");
    }


    public boolean applyNextAction(MinecraftServer server, UUID npcId, String fallbackName) {
        Villager villager = findVillager(server, npcId);
        if (villager == null) {
            if (activeDeliveries.containsKey(npcId)
                    || activeMineToChest.containsKey(npcId)
                    || activeMineToPlayer.containsKey(npcId)
                    || activeSearchTasks.containsKey(npcId)
                    || actionOutbox.containsKey(npcId)) {
                logger.warn("No villager entity found for bound agent {}", npcId);
            }
            return false;
        }

        MineToPlayerTask mineToPlayer = activeMineToPlayer.get(npcId);
        if (mineToPlayer != null) {
            boolean active = advanceMineToPlayerTask(server, villager, mineToPlayer);
            if (!active) {
                activeMineToPlayer.remove(npcId);
            }
            return active;
        }

        MineToChestTask mineTask = activeMineToChest.get(npcId);
        if (mineTask != null) {
            boolean active = advanceMineToChestTask(server, villager, mineTask);
            if (!active) {
                activeMineToChest.remove(npcId);
            }
            return active;
        }

        DeliveryTask task = activeDeliveries.get(npcId);
        if (task != null) {
            boolean active = advanceDeliveryTask(server, villager, task);
            if (!active) {
                activeDeliveries.remove(npcId);
            }
            return active;
        }

        SearchTask searchTask = activeSearchTasks.get(npcId);
        if (searchTask != null) {
            boolean active = advanceSearchTask(server, villager, fallbackName, searchTask);
            if (!active) {
                activeSearchTasks.remove(npcId);
            }
            return active;
        }

        ScoutTask scoutTask = activeScoutTasks.get(npcId);
        if (scoutTask != null) {
            boolean active = advanceScoutTask(server, villager, fallbackName, scoutTask);
            if (!active) {
                activeScoutTasks.remove(npcId);
            }
            return active;
        }

        ScenarioTask scenarioTask = activeScenarioTasks.get(npcId);
        if (scenarioTask != null) {
            boolean active = advanceScenarioTask(server, villager, fallbackName, scenarioTask);
            if (!active) {
                activeScenarioTasks.remove(npcId);
            }
            return active;
        }

        JsonObject next = pollNextAction(npcId);
        if (next == null) {
            return false;
        }

        String intent = next.has("intent") ? next.get("intent").getAsString() : "idle";
        JsonObject parameters = next.has("parameters") && next.get("parameters").isJsonObject()
                ? next.getAsJsonObject("parameters")
                : new JsonObject();
        String reasoning = next.has("reasoning") ? next.get("reasoning").getAsString() : "";

        boolean success = switch (intent) {
            case "dialogue_reply" -> executeDialogue(server, villager, fallbackName, parameters, reasoning);
                    case "recipe_reply" -> executeDialogue(server, villager, fallbackName, parameters, reasoning);
                    case "scout_explorer" -> startScoutTask(server, npcId, villager, parameters);
                    case "move_to" -> executeMove(server, villager, parameters);
                    case "fetch_from_chest" -> startFetchTask(server, npcId, villager, parameters);
                    case "mine_block" -> executeMineBlock(server, villager, parameters);
                    case "mine_to_chest" -> startMineToChestTask(server, npcId, villager, parameters);
                    case "mine_to_player" -> startMineToPlayerTask(server, npcId, villager, parameters);
                    case "build_structure" -> startScenarioDirect(server, npcId, villager, fallbackName, parameters, reasoning);
                    default -> true;
                };

        logger.info("Action execution: npc={} intent={} success={}", fallbackName, intent, success);
        return success;
    }

    public void enforceOwnerLeash(MinecraftServer server, UUID npcId, UUID ownerPlayerId, String fallbackName) {
        if (ownerPlayerId == null) {
            return;
        }
        if (activeScoutTasks.containsKey(npcId)) {
            return;
        }
        if (activeSearchTasks.containsKey(npcId)) {
            return;
        }
        if (activeScenarioTasks.containsKey(npcId)) {
            return;
        }
        Villager villager = findVillager(server, npcId);
        if (villager == null) {
            return;
        }
        ServerPlayer owner = server.getPlayerList().getPlayer(ownerPlayerId);
        if (owner == null || owner.level() != villager.level()) {
            return;
        }

        double distance = villager.distanceToSqr(owner);
        if (distance <= OWNER_IDLE_RADIUS_SQR) {
            return;
        }

        if (distance >= OWNER_TELEPORT_RADIUS_SQR) {
            villager.teleportTo(owner.getX(), owner.getY(), owner.getZ());
            logger.info("Owner leash teleported npc={} back to owner", fallbackName);
            return;
        }

        if (distance >= OWNER_PULL_RADIUS_SQR) {
            villager.getNavigation().moveTo(owner.getX(), owner.getY(), owner.getZ(), 0.9);
        }
    }

    public void maybeSendTradeGreeting(
            MinecraftServer server,
            UUID npcId,
            String fallbackName,
            UUID ownerPlayerId,
            MemoryContext memory) {
        if (ownerPlayerId == null) {
            return;
        }
        if (hasActiveWork(npcId)) {
            return;
        }
        Villager villager = findVillager(server, npcId);
        if (villager == null) {
            return;
        }
        ServerPlayer owner = server.getPlayerList().getPlayer(ownerPlayerId);
        if (owner == null || owner.level() != villager.level()) {
            return;
        }
        if (villager.distanceToSqr(owner) > TRADE_GREETING_RADIUS_SQR) {
            return;
        }

        TradeSessionKey key = new TradeSessionKey(npcId, ownerPlayerId);
        long now = System.currentTimeMillis();
        long allowedAt = nextTradeGreetingAt.getOrDefault(key, 0L);
        if (now < allowedAt) {
            return;
        }

        TradeSession session = tradeSessions.computeIfAbsent(key, ignored -> new TradeSession());
        if (session.activeOffer != null) {
            if (now <= session.activeOffer.expiresAtMillis()) {
                return;
            }
            session.activeOffer = null;
        }
        if (now - session.lastInteractionAtMillis < TRADE_RECENT_INTERACTION_SUPPRESS_MILLIS) {
            return;
        }

        boolean isFirstContactAfterJoin = session.lastInteractionAtMillis == 0L;
        nextTradeGreetingAt.put(key, now + TRADE_GREETING_COOLDOWN_MILLIS);
        session.lastInteractionAtMillis = now;
        String relationshipGreeting = isFirstContactAfterJoin && hasLongTermMemory(memory)
                ? generateRelationshipGreeting(npcId, villager, owner, memory)
                : "";
        if (!relationshipGreeting.isBlank()) {
            speakAsNpc(server, villager, fallbackName, relationshipGreeting);
            return;
        }
        speakAsNpc(server, villager, fallbackName, "You looking to trade?");
    }

    public boolean tryFireWelcomeBack(
            MinecraftServer server,
            UUID npcId,
            String fallbackName,
            UUID ownerPlayerId,
            MemoryContext memory) {
        if (ownerPlayerId == null) return true;
        Villager villager = findVillager(server, npcId);
        if (villager == null) return false;
        ServerPlayer owner = server.getPlayerList().getPlayer(ownerPlayerId);
        if (owner == null || owner.level() != villager.level()) return false;

        if (!hasLongTermMemory(memory)) {
            speakAsNpc(server, villager, fallbackName, "Hey! Good to see you again.");
            return true;
        }
        String greeting = generateRelationshipGreeting(npcId, villager, owner, memory);
        speakAsNpc(server, villager, fallbackName, greeting.isBlank()
                ? "Hey! Good to see you again." : greeting);
        return true;
    }

    public void maybeSendEnvironmentalAdvisory(
            MinecraftServer server,
            UUID npcId,
            String fallbackName,
            UUID ownerPlayerId,
            MemoryContext memory) {
        if (ownerPlayerId == null) return;
        if (hasActiveWork(npcId)) return;
        Villager villager = findVillager(server, npcId);
        if (villager == null) return;
        ServerPlayer owner = server.getPlayerList().getPlayer(ownerPlayerId);
        if (owner == null || owner.level() != villager.level()) return;

        if (villager.distanceTo(owner) > 32.0) return;

        long now = System.currentTimeMillis();
        Long allowed = nextEnvironmentalAdvisoryAt.getOrDefault(npcId, 0L);
        if (now < allowed) return;
        if (Boolean.TRUE.equals(environmentalLlmInFlightByNpc.get(npcId))) return;

        WorldSnapshot snapshot = captureWorldSnapshot(server, npcId, fallbackName);

        String signature = buildEnvironmentalSignature(snapshot);
        String previousSignature = lastEnvironmentalSignature.get(npcId);
        String prevThreat = lastEnvironmentalThreatLevel.getOrDefault(npcId, "low");
        String currentThreat = snapshot.environment() == null ? "low" : snapshot.environment().threatLevel();

        boolean threatIncreased = threatOrdinal(currentThreat) > threatOrdinal(prevThreat);
        if (!threatIncreased && previousSignature != null && previousSignature.equals(signature)) {
            return; // no meaningful change
        }

        String prompt = promptFactory.environmentalAwarenessPrompt(fallbackName, owner.getName().getString(), snapshot, memory);

        nextEnvironmentalAdvisoryAt.put(npcId, now + ENV_ADVISORY_COOLDOWN_MILLIS);
        environmentalLlmInFlightByNpc.put(npcId, true);

        CompletableFuture.runAsync(() -> {
            try {
                String raw = llmRouter.generate(prompt);
                EnvironmentalAdvice advice = parseEnvironmentalAdvice(raw);
                if (advice != null && advice.severity != null && ("medium".equalsIgnoreCase(advice.severity)
                        || "high".equalsIgnoreCase(advice.severity))) {
                    String text = advice.responseText == null ? "" : advice.responseText;
                    if (!text.isBlank()) {
                        speakAsNpc(server, villager, fallbackName, text);
                        logger.info("(llm_npc) [ENV-ADVISORY] npc={} severity={} evidence={} advisory=\"{}\"",
                                fallbackName, advice.severity, advice.evidence, text);
                    }
                } else {
                    logger.debug("(llm_npc) [ENV-ADVISORY] npc={} low-or-no-advisory -> {}", fallbackName, raw);
                }

                lastEnvironmentalSignature.put(npcId, signature);
                lastEnvironmentalThreatLevel.put(npcId, currentThreat);

            } catch (Exception e) {
                logger.warn("(llm_npc) environmental advisory failed for npc={}: {}", fallbackName, e.getMessage());
            } finally {
                environmentalLlmInFlightByNpc.put(npcId, false);
            }
        });
    }

    public boolean tryHandleRecipeInstruction(
            MinecraftServer server,
            UUID npcId,
            String fallbackName,
            String playerName,
            UUID ownerPlayerId,
            String instruction,
            MemoryContext memory,
            WorldSnapshot snapshot) {
        if (instruction == null || instruction.isBlank()) {
            return false;
        }
        String lower = instruction.toLowerCase(Locale.ROOT);
        Villager villager = findVillager(server, npcId);
        if (villager == null) {
            return false;
        }
        ServerPlayer player = findPlayerByName(server, playerName);
        if (player == null || player.level() != villager.level()) {
            return false;
        }
        if (ownerPlayerId != null && !ownerPlayerId.equals(player.getUUID())) {
            return false;
        }

        if (!isRecipeQueryWithLlm(villager, player, instruction)) {
            return false;
        }

        Item targetOutput = recipeTargetItem(lower, instruction);
        if (targetOutput == null) {
            return false;
        }

        RecipeSummary recipeSummary = summarizeRecipeFromGame(server, targetOutput);
        long now = System.currentTimeMillis();
        TradeSessionKey key = new TradeSessionKey(npcId, player.getUUID());
        TradeSession session = tradeSessions.computeIfAbsent(key, ignored -> new TradeSession());
        session.lastInteractionAtMillis = now;
        ServerLevel level = (ServerLevel) villager.level();
        List<RecipeIngredientStatus> ingredientStatuses = evaluateIngredientStatus(
                server,
                level,
                villager.blockPosition(),
                player,
                recipeSummary.ingredients());

        List<String> missingIngredients = new ArrayList<>();
        List<String> availableIngredients = new ArrayList<>();
        for (RecipeIngredientStatus status : ingredientStatuses) {
            String entry = status.readableName() + " " + status.nearbyCount() + "/" + status.neededCount();
            if (status.nearbyCount() >= status.neededCount()) {
                availableIngredients.add(entry);
            } else {
                missingIngredients.add(entry);
            }
        }
        String missingSummary = missingIngredients.isEmpty() ? "none" : String.join(", ", missingIngredients);
        String availableSummary = availableIngredients.isEmpty() ? "none" : String.join(", ", availableIngredients);


        StringBuilder fallbackReply = new StringBuilder();
        fallbackReply.append("You need ").append(recipeSummary.summaryText()).append(".");
        if (!ingredientStatuses.isEmpty()) {
            fallbackReply.append(" Nearby stock: ");
            List<String> stockBits = new ArrayList<>();
            for (RecipeIngredientStatus status : ingredientStatuses) {
                stockBits.add(status.readableName() + " " + status.nearbyCount() + "/" + status.neededCount());
            }
            fallbackReply.append(String.join(", ", stockBits)).append(".");
        }

        TradeOffer ingredientOffer = null;
        RecipeIngredientStatus offeredStatus = null;
        for (RecipeIngredientStatus status : ingredientStatuses) {
            if (!tradeOfferEngine.supportsItem(status.itemId()) || status.nearbyCount() <= 0) {
                continue;
            }
            int offerCount = Math.min(status.neededCount(), Math.max(1, status.nearbyCount()));
            ingredientOffer = tradeOfferEngine.quote(status.itemId(), offerCount, status.nearbyCount(), 0, false, now);
            offeredStatus = status;
            break;
        }
        if (ingredientOffer != null && offeredStatus != null) {
            session.activeOffer = ingredientOffer;
            session.lastInteractionAtMillis = now;
            session.lastRequestedItemId = ingredientOffer.itemId();
            session.lastRequestedQuantity = ingredientOffer.quantity();
            fallbackReply.append(" I can trade ")
                    .append(ingredientOffer.quantity())
                    .append(" ")
                    .append(offeredStatus.readableName())
                    .append(" for ")
                    .append(ingredientOffer.totalPrice())
                    .append(" emeralds if you want.");
        }
        fallbackReply.append(" I can help gather what is missing.");

        String nearbyFacts = summarizeIngredientFacts(ingredientStatuses);
        String requiredFacts = "recipe=" + recipeSummary.summaryText()
                + "; output_item=" + readableItemName(BuiltInRegistries.ITEM.getKey(targetOutput).toString())
                + "; nearby_ingredients=" + nearbyFacts
                + "; missing_ingredients=" + missingSummary
                + "; available_ingredients=" + availableSummary
                + "; active_offer=" + (ingredientOffer == null ? "none" : summarizeOffer(ingredientOffer));
        String prompt = promptFactory.recipeAssistantPrompt(
                villager.getName().getString(),
                player.getName().getString(),
                instruction,
                requiredFacts,
                snapshot,
                memory);
        String responseText = parseRecipeResponseText(llmRouter.generate(prompt));
        speakAsNpc(server, villager, fallbackName,
                groundedRecipeText(responseText, fallbackReply.toString(), ingredientOffer,
                        recipeSummary.summaryText(), nearbyFacts));
        lastActionSummaryByNpc.put(npcId, "Explained recipe for "
                + readableItemName(BuiltInRegistries.ITEM.getKey(targetOutput).toString())
                + ": " + recipeSummary.summaryText()
                + (missingIngredients.isEmpty() ? ". All ingredients available." : ". Missing: " + missingSummary + "."));
        return true;
    }

    public boolean tryHandleSearchInstruction(
            MinecraftServer server,
            UUID npcId,
            String fallbackName,
            String playerName,
            UUID ownerPlayerId,
            String instruction,
            MemoryContext memory) {
        if (instruction == null || instruction.isBlank()) {
            return false;
        }
        SearchRequest request = parseSearchRequestWithLlm(instruction, memory);
        if (request == null) {
            return false;
        }

        Villager villager = findVillager(server, npcId);
        if (villager == null || !(villager.level() instanceof ServerLevel)) {
            return false;
        }
        ServerPlayer player = findPlayerByName(server, playerName);
        if (player == null || player.level() != villager.level()) {
            return false;
        }
        if (ownerPlayerId != null && !ownerPlayerId.equals(player.getUUID())) {
            return false;
        }
        if (activeSearchTasks.containsKey(npcId)) {
            speakSearchStatusWithLlm(
                    server,
                    villager,
                    fallbackName,
                    player.getName().getString(),
                    "search_already_active",
                    "target=" + request.label(),
                    "I am already running a search task.");
            return true;
        }

        SearchTask task = new SearchTask(
                request.label(),
                request.entityTypeId(),
                request.requirePinkSheep(),
                player.getUUID());
        task.nextStatusAtMillis = System.currentTimeMillis() + SEARCH_STATUS_COOLDOWN_MILLIS;
        activeSearchTasks.put(npcId, task);
        lastActionSummaryByNpc.put(npcId, "Searching for " + request.label()
                + " (entity: " + request.entityTypeId() + "). Will report location when found.");
        suppressTradeGreeting(npcId, player.getUUID(), SEARCH_TRADE_SUPPRESS_MILLIS);
        speakSearchStatusWithLlm(
                server,
                villager,
                fallbackName,
                player.getName().getString(),
                "search_start",
                "target=" + request.label(),
                "Got it. I will search for " + request.label() + " and call out its location when I find it.");
        return true;
    }

    public boolean tryHandleScoutInstruction(
            MinecraftServer server,
            UUID npcId,
            String fallbackName,
            String playerName,
            UUID ownerPlayerId,
            String instruction,
            MemoryContext memory,
            WorldSnapshot snapshot) {
        if (instruction == null || instruction.isBlank()) {
            return false;
        }

        Villager villager = findVillager(server, npcId);
        if (villager == null || !(villager.level() instanceof ServerLevel)) {
            return false;
        }
        ServerPlayer player = findPlayerByName(server, playerName);
        if (player == null || player.level() != villager.level()) {
            return false;
        }
        if (ownerPlayerId != null && !ownerPlayerId.equals(player.getUUID())) {
            return false;
        }
        if (activeScoutTasks.containsKey(npcId)) {
            speakAsNpc(server, villager, fallbackName, "I am already scouting.");
            return true;
        }

        String prompt = promptFactory.scoutIntentPrompt(
                villager.getName().getString(),
                player.getName().getString(),
                instruction,
                snapshot,
                memory);
        AgentDecision scoutDecision = new com.example.ai.intent.AgentDecisionParser().parse(llmRouter.generate(prompt));
        if (scoutDecision.intent() != com.example.ai.intent.AgentIntentType.SCOUT_EXPLORER) {
            return false;
        }

        if (!startScoutTask(server, npcId, villager, player.getUUID(), player.getName().getString(), instruction,
                scoutDecision.parameters())) {
            return false;
        }

        String direction = scoutDecision.parameters().has("direction")
                ? scoutDecision.parameters().get("direction").getAsString()
                : "around";
        String distance = scoutDecision.parameters().has("distance")
                ? String.valueOf(Math.max(24, scoutDecision.parameters().get("distance").getAsInt()))
                : String.valueOf(SCOUT_BASE_DISTANCE);
        lastActionSummaryByNpc.put(npcId, "Scouting " + direction + " for approximately "
                + distance + " blocks to observe the area.");
        speakScoutStatus(
                server,
                villager,
                fallbackName,
                player.getName().getString(),
                "scout_start",
                "direction=" + direction + "; distance=" + distance + "; objective=" + instruction,
                "I will scout the area and report back.");
        return true;
    }

    public boolean tryHandleTradeInstruction(
            MinecraftServer server,
            UUID npcId,
            String fallbackName,
            String playerName,
            UUID ownerPlayerId,
            String instruction,
            MemoryContext memory,
            WorldSnapshot snapshot) {
        Villager villager = findVillager(server, npcId);
        if (villager == null) {
            return false;
        }
        ServerPlayer player = findPlayerByName(server, playerName);
        if (player == null || player.level() != villager.level()) {
            return false;
        }
        if (ownerPlayerId != null && !ownerPlayerId.equals(player.getUUID())) {
            return false;
        }

        TradeSessionKey key = new TradeSessionKey(npcId, player.getUUID());
        TradeSession session = tradeSessions.computeIfAbsent(key, ignored -> new TradeSession());
        long now = System.currentTimeMillis();

        if (session.activeOffer != null && now > session.activeOffer.expiresAtMillis()) {
            session.activeOffer = null;
            session.lastInteractionAtMillis = now;
            speakAsNpc(server, villager, fallbackName, "That offer expired. Ask again and I'll make a new one.");
            return true;
        }

        ParsedTradeIntent llmClassified = classifyTradeIntentWithLlm(server, villager, player, instruction, session,
                snapshot, memory, npcId);
        ParsedTradeIntent tradeIntent = resolveTradeIntent(llmClassified, session);
        logger.info(
                "[TRADE-DEBUG] npc={} utterance='{}' llm_intent={} resolved_intent={} resolved_item={} resolved_qty={} resolved_counter={}",
                fallbackName,
                instruction,
                llmClassified.type().name().toLowerCase(Locale.ROOT),
                tradeIntent.type().name().toLowerCase(Locale.ROOT),
                tradeIntent.itemId(),
                tradeIntent.quantity(),
                tradeIntent.counterTotalPrice());
        if (tradeIntent.type() == TradeIntentType.NONE && session.activeOffer == null) {
            return false;
        }
        lastTradeIntentByNpc.put(npcId, tradeIntent.type().name().toLowerCase());

        if (tradeIntent.type() == TradeIntentType.ACCEPT_OFFER) {
            return handleTradeAccept(server, villager, fallbackName, player, session, null);
        }
        if (tradeIntent.type() == TradeIntentType.DECLINE_OFFER) {
            session.activeOffer = null;
            speakAsNpc(server, villager, fallbackName, "No worries. Let me know if you want to trade later.");
            session.lastInteractionAtMillis = now;
            return true;
        }

        String mode = tradeIntent.type() == TradeIntentType.NONE ? "clarify" : tradeIntent.type().name().toLowerCase();
        String prompt = promptFactory.tradeNegotiationPrompt(
                villager.getName().getString(),
                player.getName().getString(),
                instruction,
                mode,
                session.activeOffer == null ? "" : summarizeOffer(session.activeOffer),
                tradeStockSummary(server, villager),
                tradeFactsForIntent(tradeIntent, session, instruction),
                snapshot,
                memory);
        tradeLlmInFlightByNpc.put(npcId, true);
        TradeNegotiationDraft draft;
        try {
            draft = tradeNegotiationParser.parse(llmRouter.generate(prompt));
        } finally {
            tradeLlmInFlightByNpc.put(npcId, false);
        }
        if (tradeIntent.type() == TradeIntentType.NONE) {
            if (session.activeOffer != null) {
                session.lastInteractionAtMillis = now;
                speakAsNpc(server, villager, fallbackName,
                        groundedActiveOfferText(draft.responseText(), session.activeOffer));
                return true;
            }
            speakAsNpc(server, villager, fallbackName,
                    fallbackOrDefault(draft.responseText(), "Tell me the item and amount you want to buy."));
            session.lastInteractionAtMillis = now;
            return true;
        }
        session.lastInteractionAtMillis = now;

        return switch (tradeIntent.type()) {
            case INQUIRE_STOCK -> handleStockInquiry(server, villager, fallbackName, playerName, session, tradeIntent, now, draft);
            case INQUIRE_PAYMENT -> handlePaymentInquiry(server, villager, fallbackName, draft);
            case INQUIRE_SESSION_STATUS -> handleSessionStatusInquiry(server, villager, fallbackName, session, draft);
            case REQUEST_OFFER -> handleTradeRequest(server, villager, fallbackName, playerName, session, tradeIntent, now, draft);
            case COUNTER_OFFER -> (session.activeOffer == null && tradeOfferEngine.supportsItem(tradeIntent.itemId()))
                    ? handleCounterWithoutOffer(server, villager, fallbackName, playerName, session, tradeIntent, now, draft)
                    : handleTradeCounter(server, villager, fallbackName, session, tradeIntent, now, draft);
            case ACCEPT_OFFER, DECLINE_OFFER -> false; // Already handled above
            case NONE -> false;
        };
    }

    private boolean startScoutTask(
            MinecraftServer server,
            UUID npcId,
            Villager villager,
            UUID requesterId,
            String requesterName,
            String objective,
            JsonObject parameters) {
        if (!(villager.level() instanceof ServerLevel level)) {
            return false;
        }
        if (requesterId == null) {
            return false;
        }
        ServerPlayer requester = server.getPlayerList().getPlayer(requesterId);
        if (requester == null || requester.level() != villager.level()) {
            return false;
        }

        String direction = normalizeScoutDirection(parameters, requester);
        int distance = normalizeScoutDistance(parameters);
        String focus = parameters != null && parameters.has("focus") ? parameters.get("focus").getAsString() : "anything";
        BlockPos waypoint = pickScoutWaypoint(level, requester, direction, distance);
        ScoutTask task = new ScoutTask(requesterId, requesterName, objective, direction, distance, focus, waypoint);
        activeScoutTasks.put(npcId, task);
        task.nextStatusAtMillis = System.currentTimeMillis() + SCOUT_STATUS_COOLDOWN_MILLIS;
        return true;
    }

    private boolean startScoutTask(
            MinecraftServer server,
            UUID npcId,
            Villager villager,
            JsonObject parameters) {
        ServerPlayer requester = nearestPlayer(server, villager);
        if (requester == null) {
            return false;
        }
        String objective = parameters != null && parameters.has("objective")
                ? parameters.get("objective").getAsString()
                : "";
        return startScoutTask(
                server,
                npcId,
                villager,
                requester.getUUID(),
                requester.getName().getString(),
                objective,
                parameters);
    }

    private boolean advanceScoutTask(MinecraftServer server, Villager villager, String fallbackName, ScoutTask task) {
        if (!(villager.level() instanceof ServerLevel level)) {
            return false;
        }
        ServerPlayer requester = server.getPlayerList().getPlayer(task.requesterId);
        if (requester == null || requester.level() != villager.level()) {
            speakAsNpc(server, villager, fallbackName, "I lost track of the requester, so I am stopping the scout.");
            return false;
        }

        return switch (task.phase) {
            case TRAVEL_OUTBOUND -> {
                long nowMs = System.currentTimeMillis();
                if (task.outboundDeadlineMillis == 0L) {
                    task.outboundDeadlineMillis = nowMs + 45_000L;
                }
                boolean moving = villager.getNavigation().moveTo(
                        task.waypoint.getX() + 0.5,
                        task.waypoint.getY() + 0.5,
                        task.waypoint.getZ() + 0.5,
                        0.9);
                double distance = villager.distanceToSqr(
                        task.waypoint.getX() + 0.5,
                        task.waypoint.getY() + 0.5,
                        task.waypoint.getZ() + 0.5);
                boolean arrived = distance <= 9.0 || (!moving && distance <= 36.0);
                boolean timedOut = nowMs >= task.outboundDeadlineMillis;
                if (arrived || timedOut) {
                    task.surveySnapshot = captureWorldSnapshot(server, villager.getUUID(), fallbackName);
                    task.surveyTicksRemaining = SCOUT_SURVEY_TICKS;
                    task.phase = ScoutPhase.SURVEY_AREA;
                    speakScoutStatus(
                            server,
                            villager,
                            fallbackName,
                            requester.getName().getString(),
                            "scout_scan",
                            "direction=" + task.direction + "; distance=" + task.distance + "; focus=" + task.focus,
                            "I have reached the area and am scanning now.");
                }
                yield true;
            }
            case SURVEY_AREA -> {
                if (task.surveySnapshot == null) {
                    task.surveySnapshot = captureWorldSnapshot(server, villager.getUUID(), fallbackName);
                }
                task.surveyTicksRemaining -= 1;
                if (task.surveyTicksRemaining <= 0) {
                    task.phase = ScoutPhase.RETURN_TO_PLAYER;
                    speakScoutStatus(
                            server,
                            villager,
                            fallbackName,
                            requester.getName().getString(),
                            "scout_return",
                            "direction=" + task.direction + "; distance=" + task.distance,
                            "I am heading back with what I found.");
                }
                yield true;
            }
            case RETURN_TO_PLAYER -> {
                long nowMs = System.currentTimeMillis();
                if (task.returnDeadlineMillis == 0L) {
                    task.returnDeadlineMillis = nowMs + 45_000L;
                }
                if (villager.getNavigation().isDone()) {
                    villager.getNavigation().moveTo(requester.getX(), requester.getY(), requester.getZ(), 0.9);
                }
                if (villager.distanceToSqr(requester) <= 9.0) {
                    task.phase = ScoutPhase.REPORT;
                } else if (nowMs >= task.returnDeadlineMillis) {
                    villager.teleportTo(requester.getX() + 1.0, requester.getY(), requester.getZ());
                    task.phase = ScoutPhase.REPORT;
                }
                yield true;
            }
            case REPORT -> {
                String surveyFacts = buildScoutFacts(task.surveySnapshot);
                String prompt = promptFactory.scoutReportPrompt(
                        villager.getName().getString(),
                        requester.getName().getString(),
                        task.objective,
                        task.direction,
                        String.valueOf(task.distance),
                        surveyFacts);
                String response = parseScoutReportText(llmRouter.generate(prompt));
                String text = response.isBlank() ? "I scouted the area and returned." : response;
                speakAsNpc(server, villager, fallbackName, text);
                task.phase = ScoutPhase.DONE;
                yield false;
            }
            case DONE -> false;
        };
    }

    private String parseScoutReportText(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        try {
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            for (String key : new String[]{"response_text", "text", "reply", "message", "report"}) {
                if (root.has(key) && root.get(key).isJsonPrimitive()) {
                    String val = root.get(key).getAsString().trim();
                    if (!val.isBlank()) return val;
                }
            }
            return "";
        } catch (Exception ignored) {
            return raw.trim();
        }
    }

    private String buildScoutFacts(WorldSnapshot snapshot) {
        if (snapshot == null) {
            return "none";
        }
        List<String> parts = new ArrayList<>();
        if (snapshot.environment() != null) {
            parts.add("biome=" + snapshot.environment().biome());
            parts.add("threat_level=" + snapshot.environment().threatLevel());
            parts.add("weather=" + snapshot.environment().weather());
        }
        if (snapshot.nearbyEntities() != null && !snapshot.nearbyEntities().isEmpty()) {
            List<String> entities = new ArrayList<>();
            for (WorldSnapshot.SeenEntity entity : snapshot.nearbyEntities()) {
                entities.add(entity.type() + "@" + (int) Math.round(entity.distance()));
            }
            parts.add("nearby_entities=" + String.join(", ", entities));
        }
        if (snapshot.nearbyBlockHistogram() != null && !snapshot.nearbyBlockHistogram().isEmpty()) {
            List<String> blocks = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : snapshot.nearbyBlockHistogram().entrySet()) {
                blocks.add(entry.getKey() + "=" + entry.getValue());
                if (blocks.size() >= 6) {
                    break;
                }
            }
            parts.add("nearby_blocks=" + String.join(", ", blocks));
        }
        return parts.isEmpty() ? "none" : String.join("; ", parts);
    }

    private String normalizeScoutDirection(JsonObject parameters, ServerPlayer requester) {
        if (parameters == null || !parameters.has("direction")) {
            return "around";
        }
        String direction = parameters.get("direction").getAsString().toLowerCase(Locale.ROOT).trim();
        return switch (direction) {
            case "north", "south", "east", "west", "forward", "around" -> direction;
            default -> "around";
        };
    }

    private int normalizeScoutDistance(JsonObject parameters) {
        if (parameters == null || !parameters.has("distance")) {
            return SCOUT_BASE_DISTANCE;
        }
        int distance = Math.max(24, parameters.get("distance").getAsInt());
        return Math.min(SCOUT_MAX_DISTANCE, distance);
    }

    private BlockPos pickScoutWaypoint(ServerLevel level, ServerPlayer requester, String direction, int distance) {
        BlockPos anchor = requester.blockPosition();
        Vec3 vector = switch (direction) {
            case "north" -> new Vec3(0, 0, -1);
            case "south" -> new Vec3(0, 0, 1);
            case "east" -> new Vec3(1, 0, 0);
            case "west" -> new Vec3(-1, 0, 0);
            case "forward" -> {
                Vec3 look = requester.getLookAngle();
                yield new Vec3(look.x, 0, look.z);
            }
            default -> {
                double angle = requester.getRandom().nextDouble() * Math.PI * 2.0;
                yield new Vec3(Math.cos(angle), 0, Math.sin(angle));
            }
        };
        Vec3 normalized = vector.lengthSqr() < 0.0001 ? new Vec3(1, 0, 0) : vector.normalize();
        int dx = (int) Math.round(normalized.x * distance);
        int dz = (int) Math.round(normalized.z * distance);
        BlockPos probe = anchor.offset(dx, 0, dz);
        BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, probe);
        return surface.above();
    }

    private void speakScoutStatus(
            MinecraftServer server,
            Villager villager,
            String fallbackName,
            String playerName,
            String eventType,
            String requiredFacts,
            String fallbackText) {
        String prompt = promptFactory.scoutStatusPrompt(
                villager.getName().getString(),
                playerName,
                eventType,
                requiredFacts);
        String response = parseScoutReportText(llmRouter.generate(prompt));
        speakAsNpc(server, villager, fallbackName, response.isBlank() ? fallbackText : response);
    }

    private boolean handleSessionStatusInquiry(
            MinecraftServer server,
            Villager villager,
            String fallbackName,
            TradeSession session,
            TradeNegotiationDraft draft) {
        if (session.activeOffer != null) {
            speakAsNpc(server, villager, fallbackName,
                    groundedActiveOfferText(draft.responseText(), session.activeOffer));
            return true;
        }
        speakAsNpc(server, villager, fallbackName,
                fallbackOrDefault(draft.responseText(), "No active offer right now."));
        return true;
    }

    private boolean handlePaymentInquiry(
            MinecraftServer server,
            Villager villager,
            String fallbackName,
            TradeNegotiationDraft draft) {
        speakAsNpc(server, villager, fallbackName,
                fallbackOrDefault(draft.responseText(), "I only accept emeralds right now."));
        return true;
    }

    private boolean handleStockInquiry(
            MinecraftServer server,
            Villager villager,
            String fallbackName,
            String playerName,
            TradeSession session,
            ParsedTradeIntent tradeIntent,
            long now,
            TradeNegotiationDraft draft) {
        ServerLevel level = (ServerLevel) villager.level();
        ServerPlayer player = findPlayerByName(server, playerName);
        BlockPos scanCenter = (player != null && player.level() == level)
                ? player.blockPosition() : villager.blockPosition();
        String requestedItemId = tradeIntent == null ? "" : tradeIntent.itemId();
        if (requestedItemId != null && !requestedItemId.isBlank() && tradeOfferEngine.supportsItem(requestedItemId)) {
            Item requestedItem = resolveKnownItem(requestedItemId);
            if (requestedItem != null) {
                int stock = countItemInNearbyChests(server, level, scanCenter, requestedItem, TRADE_SCAN_RADIUS);
                session.lastRequestedItemId = requestedItemId;
                session.lastRequestedQuantity = 1;
                if (stock <= 0) {
                    String msg = parseRecipeResponseText(llmRouter.generate(
                            promptFactory.tradeOutOfStockPrompt(villager.getName().getString(),
                                    playerName, requestedItemId, 0)));
                    speakAsNpc(server, villager, fallbackName,
                            msg.isBlank() ? "I'm afraid I don't have any of that in stock." : msg);
                    return true;
                }
                TradeOffer preview = tradeOfferEngine.quote(requestedItemId, 1, stock, 0, false, now);
                session.activeOffer = preview;
                session.lastRequestedQuantity = 1;
                String response = "I currently have " + stock + " " + readableItemName(requestedItemId)
                        + " in stock at " + preview.unitPrice() + " emerald each.";
                speakAsNpc(server, villager, fallbackName, groundedStockText(draft.responseText(), response));
                return true;
            }
        }

        List<String> offers = new ArrayList<>();
        String firstItemIdWithStock = null;
        int firstStock = 0;
        for (String itemId : tradeOfferEngine.supportedItems()) {
            Item item = resolveKnownItem(itemId);
            if (item == null) {
                continue;
            }
            int stock = countItemInNearbyChests(server, level, scanCenter, item, TRADE_SCAN_RADIUS);
            if (stock <= 0) {
                continue;
            }
            if (firstItemIdWithStock == null) {
                firstItemIdWithStock = itemId;
                firstStock = stock;
            }
            TradeOffer preview = tradeOfferEngine.quote(itemId, 1, stock, 0, false, now);
            offers.add(
                    readableItemName(itemId) + " (" + stock + " in stock, " + preview.unitPrice() + " emerald each)");
        }
        if (offers.isEmpty()) {
            session.activeOffer = null;
            speakAsNpc(server, villager, fallbackName, "I'm out of stock right now.");
            return true;
        }
        session.lastRequestedItemId = firstItemIdWithStock;
        session.lastRequestedQuantity = 1;
        session.activeOffer = tradeOfferEngine.quote(firstItemIdWithStock, 1, firstStock, 0, false, now);
        offers.sort(String::compareTo);
        int limit = Math.min(4, offers.size());
        String message = String.join(", ", offers.subList(0, limit));
        if (offers.size() > limit) {
            message = message + ", and more.";
        }
        speakAsNpc(server, villager, fallbackName,
                groundedStockText(draft.responseText(), "I currently sell: " + message));
        return true;
    }

    private boolean handleTradeRequest(
            MinecraftServer server,
            Villager villager,
            String fallbackName,
            String playerName,
            TradeSession session,
            ParsedTradeIntent tradeIntent,
            long now,
            TradeNegotiationDraft draft) {
        String itemId = tradeIntent.itemId();
        int quantity = Math.max(1, tradeIntent.quantity());
        if (itemId == null || itemId.isBlank() || itemId.equals("none")
                || itemId.equals("minecraft:none") || itemId.equals("minecraft:air")) {
            return handleStockInquiry(server, villager, fallbackName, playerName, session,
                    new ParsedTradeIntent(TradeIntentType.INQUIRE_STOCK, "", 0, null), now, draft);
        }
        if (!tradeOfferEngine.supportsItem(itemId)) {
            speakAsNpc(server, villager, fallbackName, "I don't trade that item right now.");
            return true;
        }

        int stock = countItemInNearbyChests(server, (ServerLevel) villager.level(), villager.blockPosition(),
                resolveKnownItem(itemId), TRADE_SCAN_RADIUS);
        if (stock < quantity) {
            String msg = parseRecipeResponseText(llmRouter.generate(
                    promptFactory.tradeOutOfStockPrompt(villager.getName().getString(),
                            playerName, itemId, stock)));
            speakAsNpc(server, villager, fallbackName,
                    msg.isBlank() ? "I only have " + stock + " of that available, not enough for your order." : msg);
            return true;
        }

        TradeOffer offer = tradeOfferEngine.quote(itemId, quantity, stock, 0, false, now);
        TradeNegotiationDraft pricingDraft = draft;
        if (pricingDraft != null
                && (pricingDraft.suggestedUnitPrice() != null || pricingDraft.suggestedTotalPrice() != null)) {
            offer = tradeOfferEngine.quote(
                    itemId,
                    quantity,
                    stock,
                    0,
                    false,
                    now,
                    pricingDraft.suggestedUnitPrice(),
                    pricingDraft.suggestedTotalPrice());
        }
        session.activeOffer = offer;
        session.lastRequestedItemId = itemId;
        session.lastRequestedQuantity = quantity;
        speakAsNpc(server, villager, fallbackName, groundedOfferText(draft.responseText(), offer));
        return true;
    }

    private boolean handleTradeCounter(
            MinecraftServer server,
            Villager villager,
            String fallbackName,
            TradeSession session,
            ParsedTradeIntent tradeIntent,
            long now,
            TradeNegotiationDraft draft) {
        if (session.activeOffer == null) {
            speakAsNpc(server, villager, fallbackName, "I need to make an offer first.");
            return true;
        }
        int requestedQty = tradeIntent.quantity() > 0 ? tradeIntent.quantity() : session.activeOffer.quantity();
        if (requestedQty != session.activeOffer.quantity()) {
            Item reqItem = resolveKnownItem(session.activeOffer.itemId());
            if (reqItem != null) {
                int stock = countItemInNearbyChests(server, (ServerLevel) villager.level(),
                        villager.blockPosition(), reqItem, TRADE_SCAN_RADIUS);
                if (stock >= requestedQty) {
                    session.activeOffer = tradeOfferEngine.quote(
                            session.activeOffer.itemId(), requestedQty, stock, 0, false, now);
                    session.lastRequestedQuantity = requestedQty;
                }
            }
        }
        Integer proposed = tradeIntent.counterTotalPrice();
        if (proposed == null || proposed <= 0) {
            int minimum = tradeOfferEngine.minimumAcceptableTotal(session.activeOffer);
            int autoCounter = Math.max(minimum,
                    session.activeOffer.totalPrice() - Math.max(1, session.activeOffer.totalPrice() / 10));
            if (draft.counterTotalPrice() != null && draft.counterTotalPrice() >= minimum) {
                autoCounter = draft.counterTotalPrice();
            }
            TradeOffer autoOffer = new TradeOffer(
                    session.activeOffer.itemId(),
                    session.activeOffer.quantity(),
                    Math.max(1, autoCounter / Math.max(1, session.activeOffer.quantity())),
                    autoCounter,
                    session.activeOffer.maxDiscountPct(),
                    session.activeOffer.expiresAtMillis());
            session.activeOffer = autoOffer;
            speakAsNpc(server, villager, fallbackName, groundedCounterText(draft.responseText(), autoOffer));
            return true;
        }
        TradeCounterResult counter = tradeOfferEngine.evaluateCounter(session.activeOffer, proposed, now);
        if (!counter.accepted()) {
            int bobsTotal = counter.minimumAcceptableTotal();
            int qty = session.activeOffer.quantity();
            session.activeOffer = new TradeOffer(
                    session.activeOffer.itemId(),
                    qty,
                    Math.max(1, bobsTotal / Math.max(1, qty)),
                    bobsTotal,
                    session.activeOffer.maxDiscountPct(),
                    session.activeOffer.expiresAtMillis());
            speakAsNpc(server, villager, fallbackName, groundedCounterRejectionText(
                    draft.responseText(),
                    bobsTotal,
                    qty,
                    session.activeOffer.itemId()));
            return true;
        }

        session.activeOffer = counter.offer();
        speakAsNpc(server, villager, fallbackName, groundedCounterText(draft.responseText(), counter.offer()));
        return true;
    }

    private boolean handleCounterWithoutOffer(
            MinecraftServer server,
            Villager villager,
            String fallbackName,
            String playerName,
            TradeSession session,
            ParsedTradeIntent tradeIntent,
            long now,
            TradeNegotiationDraft draft) {
        String itemId = tradeIntent.itemId();
        int quantity = Math.max(1, tradeIntent.quantity());
        Item item = resolveKnownItem(itemId);
        if (item == null) {
            speakAsNpc(server, villager, fallbackName, "I don't trade that item.");
            return true;
        }
        int stock = countItemInNearbyChests(server, (ServerLevel) villager.level(), villager.blockPosition(),
                item, TRADE_SCAN_RADIUS);
        if (stock < quantity) {
            String msg = parseRecipeResponseText(llmRouter.generate(
                    promptFactory.tradeOutOfStockPrompt(villager.getName().getString(), playerName, itemId, stock)));
            speakAsNpc(server, villager, fallbackName,
                    msg.isBlank() ? "I only have " + stock + " of that available." : msg);
            return true;
        }
        TradeOffer freshOffer = tradeOfferEngine.quote(itemId, quantity, stock, 0, false, now);
        session.lastRequestedItemId = itemId;
        session.lastRequestedQuantity = quantity;
        Integer proposed = tradeIntent.counterTotalPrice();
        if (proposed != null && proposed > 0) {
            TradeCounterResult result = tradeOfferEngine.evaluateCounter(freshOffer, proposed, now);
            if (result.accepted()) {
                session.activeOffer = result.offer();
                speakAsNpc(server, villager, fallbackName, groundedCounterText(draft.responseText(), result.offer()));
                return true;
            }
            session.activeOffer = freshOffer;
            speakAsNpc(server, villager, fallbackName, groundedCounterRejectionText(
                    draft.responseText(), result.minimumAcceptableTotal(), quantity, itemId));
            return true;
        }
        session.activeOffer = freshOffer;
        speakAsNpc(server, villager, fallbackName, groundedOfferText(draft.responseText(), freshOffer));
        return true;
    }

    private boolean handleTradeAccept(
            MinecraftServer server,
            Villager villager,
            String fallbackName,
            ServerPlayer player,
            TradeSession session,
            TradeNegotiationDraft draft) {
        if (session.activeOffer == null) {
            speakAsNpc(server, villager, fallbackName, "There's no active offer yet.");
            return true;
        }

        TradeOffer offer = session.activeOffer;
        if (!hasAtLeast(player, Items.EMERALD, offer.totalPrice())) {
            speakAsNpc(server, villager, fallbackName, "You don't have enough emeralds for that trade.");
            return true;
        }
        Item item = resolveKnownItem(offer.itemId());
        if (item == null) {
            speakAsNpc(server, villager, fallbackName, "I can't complete that trade right now.");
            return true;
        }
        ServerLevel level = (ServerLevel) villager.level();
        int stock = countItemInNearbyChests(server, level, villager.blockPosition(), item, TRADE_SCAN_RADIUS);
        if (stock < offer.quantity()) {
            session.activeOffer = null;
            speakAsNpc(server, villager, fallbackName, "I ran out of stock before completing the deal.");
            return true;
        }

        boolean paid = removeFromInventory(player, Items.EMERALD, offer.totalPrice());
        if (!paid) {
            speakAsNpc(server, villager, fallbackName, "I couldn't take payment. Try again.");
            return true;
        }
        ItemStack collected = withdrawFromNearbyChests(server, level, villager.blockPosition(), item, TRADE_SCAN_RADIUS,
                offer.quantity());
        if (collected.getCount() < offer.quantity()) {
            player.getInventory().add(new ItemStack(Items.EMERALD, offer.totalPrice()));
            session.activeOffer = null;
            String draftText = draft == null ? null : draft.responseText();
            speakAsNpc(server, villager, fallbackName,
                    fallbackOrDefault(draftText, "Trade failed while collecting stock. I refunded your emeralds."));
            return true;
        }

        ItemStack remaining = insertIntoPlayerInventory(player, collected.copy());
        if (!remaining.isEmpty()) {
            Containers.dropItemStack(
                    player.level(),
                    player.getX(),
                    player.getY() + 1.0,
                    player.getZ(),
                    remaining);
        }

        BlockPos chestPos = findNearestChest(server, level, villager.blockPosition(), CHEST_SCAN_RADIUS);
        ItemStack emeraldStack = new ItemStack(Items.EMERALD, offer.totalPrice());
        if (chestPos != null) {
            ItemStack emeraldRemaining = insertIntoChest(server, level, chestPos, emeraldStack);
            if (!emeraldRemaining.isEmpty()) {
                Containers.dropItemStack(level, villager.getX(), villager.getY() + 1.0, villager.getZ(),
                        emeraldRemaining);
            }
        } else {
            Containers.dropItemStack(level, villager.getX(), villager.getY() + 1.0, villager.getZ(), emeraldStack);
        }

        session.activeOffer = null;
        String draftText = draft == null ? null : draft.responseText();
        speakAsNpc(server, villager, fallbackName,
                fallbackOrDefault(draftText, "Deal done. Pleasure trading with you."));
        return true;
    }

    private ServerPlayer findPlayerByName(MinecraftServer server, String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return null;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getName().getString().equalsIgnoreCase(playerName)) {
                return player;
            }
        }
        return null;
    }

    private String tradeStockSummary(MinecraftServer server, Villager villager) {
        if (!(villager.level() instanceof ServerLevel level)) {
            return "unavailable";
        }
        List<String> entries = new ArrayList<>();
        for (String itemId : tradeOfferEngine.supportedItems()) {
            Item item = resolveKnownItem(itemId);
            if (item == null) {
                continue;
            }
            int stock = countItemInNearbyChests(server, level, villager.blockPosition(), item, TRADE_SCAN_RADIUS);
            if (stock > 0) {
                entries.add(readableItemName(itemId) + "=" + stock);
            }
        }
        if (entries.isEmpty()) {
            return "none";
        }
        return String.join(", ", entries);
    }

    private String summarizeOffer(TradeOffer offer) {
        return readableItemName(offer.itemId())
                + " x" + offer.quantity()
                + " for " + offer.totalPrice() + " emeralds";
    }

    private String tradeFactsForIntent(ParsedTradeIntent tradeIntent, TradeSession session, String instruction) {
        if (tradeIntent.type() == TradeIntentType.REQUEST_OFFER && session.activeOffer == null) {
            String itemId = tradeIntent.itemId();
            int quantity = Math.max(1, tradeIntent.quantity());
            PriceConfig cfg = tradeOfferEngine.currentConfigs().getOrDefault(itemId, PriceConfig.defaults(2));
            return "request_item=" + itemId
                    + "; quantity=" + quantity
                    + "; base_price=" + cfg.base()
                    + "; max_price=" + cfg.max()
                    + "; discount_pct=" + cfg.discountPct()
                    + "; start_offer_at=base_price";
        }
        if (tradeIntent.type() == TradeIntentType.COUNTER_OFFER && session.activeOffer != null) {
            return "current_offer=" + summarizeOffer(session.activeOffer)
                    + "; minimum_total=" + tradeOfferEngine.minimumAcceptableTotal(session.activeOffer)
                    + "; player_text=" + instruction;
        }
        if (tradeIntent.type() == TradeIntentType.ACCEPT_OFFER && session.activeOffer != null) {
            return "active_offer=" + summarizeOffer(session.activeOffer);
        }
        return "session_active=" + (session.activeOffer != null);
    }

    private String groundedOfferText(String draftText, TradeOffer offer) {
        String fallback = "I can offer " + offer.quantity() + "x " + readableItemName(offer.itemId())
                + " for " + offer.totalPrice() + " emeralds. Deal?";
        if (draftText == null || draftText.isBlank()) {
            return fallback;
        }
        String lower = draftText.toLowerCase(Locale.ROOT);
        boolean hasItem = lower.contains(readableItemName(offer.itemId()).toLowerCase(Locale.ROOT));
        boolean hasPrice = lower.contains(String.valueOf(offer.totalPrice()));
        boolean hasCurrency = lower.contains("emerald");
        if (!hasItem || !hasPrice || !hasCurrency) {
            return fallback;
        }
        return draftText;
    }

    private String groundedCounterText(String draftText, TradeOffer offer) {
        String fallback = "Alright, " + offer.totalPrice() + " emeralds. Deal?";
        if (draftText == null || draftText.isBlank()) {
            return fallback;
        }
        String lower = draftText.toLowerCase();
        boolean hasPrice = lower.contains(String.valueOf(offer.totalPrice()));
        boolean hasCurrency = lower.contains("emerald");
        boolean hasNpcPrefix = lower.startsWith("villager:");
        boolean givesPlayerEmeralds = lower.contains("i'll give you") || lower.contains("i will give you");
        if (!hasPrice || !hasCurrency || hasNpcPrefix || givesPlayerEmeralds) {
            return fallback;
        }
        return draftText;
    }

    private String groundedCounterRejectionText(
            String draftText,
            int minimumAcceptableTotal,
            int quantity,
            String itemId) {
        String fallback = "That's too low. Lowest I can do is " + minimumAcceptableTotal + " emeralds for "
                + quantity + "x " + readableItemName(itemId) + ".";
        if (draftText == null || draftText.isBlank()) {
            return fallback;
        }
        String lower = draftText.toLowerCase();
        boolean hasMinimum = lower.contains(String.valueOf(minimumAcceptableTotal));
        boolean hasCurrency = lower.contains("emerald");
        boolean hasNpcPrefix = lower.startsWith("villager:");
        boolean givesPlayerEmeralds = lower.contains("i'll give you") || lower.contains("i will give you");
        if (!hasCurrency || hasNpcPrefix || givesPlayerEmeralds) {
            return fallback;
        }
        if (!hasMinimum) {
            return draftText + " Lowest I can do is " + minimumAcceptableTotal + " emeralds for "
                    + quantity + "x " + readableItemName(itemId) + ".";
        }
        return draftText;
    }

    private String fallbackOrDefault(String draftText, String fallback) {
        if (draftText == null || draftText.isBlank()) {
            return fallback;
        }
        return draftText;
    }

    private String groundedActiveOfferText(String draftText, TradeOffer offer) {
        String fallback = "We still have an active offer: " + summarizeOffer(offer)
                + ". Say deal, no, your counter in emeralds, or request a different quantity.";
        if (draftText == null || draftText.isBlank()) {
            return fallback;
        }
        String lower = draftText.toLowerCase();
        boolean hasPrice = lower.contains(String.valueOf(offer.totalPrice()));
        boolean hasCurrency = lower.contains("emerald");
        boolean hasItem = lower.contains(readableItemName(offer.itemId()).toLowerCase());
        boolean givesPlayerEmeralds = lower.contains("i'll give you") || lower.contains("i will give you");
        return hasPrice && hasCurrency && hasItem && !givesPlayerEmeralds ? draftText : fallback;
    }

    private String groundedStockText(String draftText, String fallback) {
        if (draftText == null || draftText.isBlank()) {
            return fallback;
        }
        String lower = draftText.toLowerCase();
        if (!lower.contains("emerald")) {
            return fallback;
        }
        return draftText;
    }

    private boolean isRecipeQueryWithLlm(Villager villager, ServerPlayer player, String instruction) {
        String prompt = promptFactory.recipeIntentClassifierPrompt(
                villager.getName().getString(),
                player.getName().getString(),
                instruction
        );
        tradeLlmInFlightByNpc.put(villager.getUUID(), true);
        String raw;
        try {
            raw = llmRouter.generate(prompt);
        } finally {
            tradeLlmInFlightByNpc.put(villager.getUUID(), false);
        }
        if (raw == null || raw.isBlank()) {
            return false;
        }
        try {
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            if (!root.has("is_recipe")) {
                return false;
            }
            return root.get("is_recipe").getAsBoolean();
        } catch (Exception ignored) {
            return false;
        }
    }

    private String parseRecipeResponseText(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        try {
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            if (root.has("response_text")) {
                return root.get("response_text").getAsString();
            }
            return "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private String groundedRecipeText(
            String draftText,
            String fallback,
            TradeOffer optionalOffer,
            String requiredRecipe,
            String requiredNearby
    ) {
        if (draftText == null || draftText.isBlank()) {
            return fallback;
        }
            String lower = draftText.toLowerCase(Locale.ROOT);
            // If there's no real offer, the LLM MUST NOT claim it. Detect obvious trade/currency language and fall back.
            if (optionalOffer == null) {
                if (lower.contains("emerald")
                        || lower.contains("i can trade")
                        || lower.contains("i can provide")
                        || lower.contains("i have") && (lower.contains("for") || lower.matches(".*\\bfor \\\\d+\\b.*"))
                        || lower.contains("offer")
                        || lower.contains("trade")
                        || lower.matches(".*\\b\\d+ emeralds\\b.*")
                ) {
                    return fallback;
                }
                return draftText;
            }

            // If there is an offer, require the reply to reference emeralds and either the price or the offered item.
            boolean hasEmerald = lower.contains("emerald");
            boolean hasPrice = lower.contains(String.valueOf(optionalOffer.totalPrice()));
            boolean hasOfferItem = lower.contains(readableItemName(optionalOffer.itemId()).toLowerCase(Locale.ROOT));
            if (!hasEmerald || (!hasPrice && !hasOfferItem)) {
                return fallback;
            }
            return draftText;
        }

    private void speakSearchStatusWithLlm(
            MinecraftServer server,
            Villager villager,
            String fallbackName,
            String playerName,
            String eventType,
            String requiredFacts,
            String fallbackText) {
        String prompt = promptFactory.searchStatusPrompt(
                villager.getName().getString(),
                playerName == null || playerName.isBlank() ? "player" : playerName,
                eventType,
                requiredFacts);
        tradeLlmInFlightByNpc.put(villager.getUUID(), true);
        try {
            String responseText = parseRecipeResponseText(llmRouter.generate(prompt));
            String grounded = groundedSearchStatusText(responseText, eventType, requiredFacts, fallbackText);
            speakAsNpc(server, villager, fallbackName, grounded);
        } finally {
            tradeLlmInFlightByNpc.put(villager.getUUID(), false);
        }
    }

    private String groundedSearchStatusText(
            String draftText,
            String eventType,
            String requiredFacts,
            String fallback) {
        if (draftText == null || draftText.isBlank()) {
            return fallback;
        }

        String lower = draftText.toLowerCase(Locale.ROOT);
        if ("target_found".equals(eventType) || "beacon_start".equals(eventType) || "beacon_update".equals(eventType)) {
            Integer x = extractFactInt(requiredFacts, "x");
            Integer y = extractFactInt(requiredFacts, "y");
            Integer z = extractFactInt(requiredFacts, "z");
            if (x != null && !lower.contains(String.valueOf(x))) {
                return fallback;
            }
            if (y != null && !lower.contains(String.valueOf(y))) {
                return fallback;
            }
            if (z != null && !lower.contains(String.valueOf(z))) {
                return fallback;
            }
        }
        return draftText;
    }

    private Integer extractFactInt(String requiredFacts, String key) {
        if (requiredFacts == null || requiredFacts.isBlank() || key == null || key.isBlank()) {
            return null;
        }
        String token = key + "=";
        int index = requiredFacts.indexOf(token);
        if (index < 0) {
            return null;
        }
        int start = index + token.length();
        int end = requiredFacts.indexOf(';', start);
        if (end < 0) {
            end = requiredFacts.length();
        }
        String value = requiredFacts.substring(start, end).trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public boolean isTradeLlmInFlight(UUID npcId) {
        return tradeLlmInFlightByNpc.getOrDefault(npcId, false);
    }

    public String lastTradeIntent(UUID npcId) {
        return lastTradeIntentByNpc.getOrDefault(npcId, "none");
    }

    public List<String> pollCompletedTaskSummaries(UUID npcId) {
        return completedTaskSummaries.remove(npcId);
    }

    private void recordTaskSummary(UUID npcId, String summary) {
        completedTaskSummaries.compute(npcId, (id, list) -> {
            List<String> safe = list == null ? new ArrayList<>() : list;
            safe.add(summary);
            return safe;
        });
    }

    public String currentTaskPhase(UUID npcId) {
        MineToPlayerTask mineToPlayer = activeMineToPlayer.get(npcId);
        if (mineToPlayer != null) {
            return "mine_to_player:" + mineToPlayer.phase.name().toLowerCase(Locale.ROOT)
                    + " (" + mineToPlayer.minedCount + "/" + mineToPlayer.count + ")";
        }
        ScenarioTask scenarioTask = activeScenarioTasks.get(npcId);
        if (scenarioTask != null) {
            return "scenario:" + scenarioTask.scenarioName
                    + " step=" + (scenarioTask.currentStepIndex + 1) + "/" + scenarioTask.steps.size();
        }
        ScoutTask scoutTask = activeScoutTasks.get(npcId);
        if (scoutTask != null) {
            return "scout:" + scoutTask.phase.name().toLowerCase(Locale.ROOT)
                    + " (" + scoutTask.direction + ", r=" + scoutTask.distance + ")";
        }
        MineToChestTask mineToChest = activeMineToChest.get(npcId);
        if (mineToChest != null) {
            return "mine_to_chest:" + mineToChest.phase.name().toLowerCase(Locale.ROOT)
                    + " (" + mineToChest.minedCount + "/" + mineToChest.count + ")";
        }
        DeliveryTask delivery = activeDeliveries.get(npcId);
        if (delivery != null) {
            return "fetch_delivery:" + delivery.phase.name().toLowerCase(Locale.ROOT)
                    + " (" + delivery.itemId + " x" + delivery.count + ")";
        }
        SearchTask searchTask = activeSearchTasks.get(npcId);
        if (searchTask != null) {
            return "search:" + searchTask.phase.name().toLowerCase(Locale.ROOT)
                    + " (" + searchTask.targetLabel + ", r=" + searchTask.searchRadius + ")";
        }
        List<JsonObject> queued = actionOutbox.get(npcId);
        if (queued != null && !queued.isEmpty()) {
            JsonObject head = queued.get(0);
            String intent = head.has("intent") ? head.get("intent").getAsString() : "unknown";
            return "queued:" + intent;
        }
        return "idle";
    }

    public String activeOfferSummary(UUID npcId, UUID ownerPlayerId) {
        TradeSession session = null;
        if (ownerPlayerId != null) {
            session = tradeSessions.get(new TradeSessionKey(npcId, ownerPlayerId));
        }
        if (session == null) {
            for (Map.Entry<TradeSessionKey, TradeSession> entry : tradeSessions.entrySet()) {
                if (entry.getKey().npcId().equals(npcId)) {
                    session = entry.getValue();
                    break;
                }
            }
        }
        if (session == null || session.activeOffer == null) {
            return "none";
        }
        return summarizeOffer(session.activeOffer);
    }

    private JsonObject pollNextAction(UUID npcId) {
        JsonObject[] holder = new JsonObject[1];
        actionOutbox.computeIfPresent(npcId, (id, list) -> {
            if (list.isEmpty()) {
                return null;
            }
            holder[0] = list.remove(0);
            return list.isEmpty() ? null : list;
        });
        return holder[0];
    }

    public boolean isVillagerAlive(MinecraftServer server, UUID npcId) {
        return findVillager(server, npcId) != null;
    }

    private Villager findVillager(MinecraftServer server, UUID npcId) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(npcId);
            if (entity instanceof Villager villager) {
                return villager;
            }
        }
        return null;
    }

    private boolean executeDialogue(
            MinecraftServer server,
            Villager villager,
            String fallbackName,
            JsonObject parameters,
            String reasoning) {
        if (!parameters.has("text")) {
            logger.info("Dialogue action rejected because parameters.text is missing.");
            return false;
        }
        String npcName = villager.getName().getString();
        if (npcName == null || npcName.isBlank()) {
            npcName = fallbackName;
        }
        String text = parameters.get("text").getAsString();
        if (text.isBlank()) {
            text = reasoning.isBlank() ? "I am ready for the next task." : reasoning;
        }

        Component message = Component.literal("[" + npcName + "] " + text);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level() == villager.level() && player.distanceToSqr(villager) <= 256.0) {
                player.sendSystemMessage(message);
            }
        }
        return true;
    }

    private boolean executeMove(MinecraftServer server, Villager villager, JsonObject parameters) {
        if (parameters.has("target_player") && parameters.get("target_player").getAsBoolean()) {
            ServerPlayer nearest = nearestPlayer(server, villager);
            if (nearest == null) {
                return false;
            }
            return villager.getNavigation().moveTo(nearest.getX(), nearest.getY(), nearest.getZ(), 0.85);
        }
        if (!parameters.has("x") || !parameters.has("y") || !parameters.has("z")) {
            return false;
        }
        double x = parameters.get("x").getAsDouble();
        double y = parameters.get("y").getAsDouble();
        double z = parameters.get("z").getAsDouble();
        return villager.getNavigation().moveTo(x, y, z, 0.85);
    }

    private ServerPlayer nearestPlayer(MinecraftServer server, Villager villager) {
        ServerPlayer nearest = null;
        double best = Double.MAX_VALUE;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level() != villager.level()) {
                continue;
            }
            double dist = player.distanceToSqr(villager);
            if (dist < best) {
                best = dist;
                nearest = player;
            }
        }
        return nearest;
    }

    private boolean startFetchTask(MinecraftServer server, UUID npcId, Villager villager, JsonObject parameters) {
        return startFetchTask(server, npcId, villager, parameters, true);
    }

    private boolean startFetchTask(MinecraftServer server, UUID npcId, Villager villager, JsonObject parameters, boolean notifyOnFailure) {
        if (!(villager.level() instanceof ServerLevel level)) {
            return false;
        }
        if (!parameters.has("item_id")) {
            return false;
        }

        String itemId = parameters.get("item_id").getAsString().toLowerCase();
        int count = parameters.has("count") ? Math.max(1, parameters.get("count").getAsInt()) : 1;
        Item item = resolveKnownItem(itemId);
        if (item == null) {
            notifyNearbyPlayers(server, villager, "[LLM NPC] Unsupported item id right now: " + itemId);
            return false;
        }

        int available = countItemInNearbyChests(server, level, villager.blockPosition(), item, CHEST_SCAN_RADIUS);
        BlockPos chestPos = available >= count
                ? findChestWithItem(level, villager.blockPosition(), item, CHEST_SCAN_RADIUS, count)
                : null;
        if (chestPos == null) {
            if (notifyOnFailure) {
                if (available > 0) {
                    notifyNearbyPlayers(server, villager, "[LLM NPC] I need " + count + "x " + itemId
                            + " but only found " + available + " in nearby chests. Add more and I will continue.");
                } else {
                    notifyNearbyPlayers(server, villager, "[LLM NPC] I could not find " + itemId
                            + " in nearby chests. Please place " + count + "x " + itemId + " in a nearby chest.");
                }
            }
            return false;
        }

        ServerPlayer target = nearestPlayer(server, villager);
        if (target == null) {
            return false;
        }

        DeliveryTask task = new DeliveryTask(item, itemId, count, chestPos, target.getUUID());
        activeDeliveries.put(npcId, task);
        notifyNearbyPlayers(server, villager, "[LLM NPC] Fetching " + count + "x " + itemId + " from chest.");
        return true;
    }

    private boolean executeMineBlock(MinecraftServer server, Villager villager, JsonObject parameters) {
        if (!(villager.level() instanceof ServerLevel level)) {
            return false;
        }

        BlockPos targetPos;
        if (parameters.has("x") && parameters.has("y") && parameters.has("z")) {
            targetPos = new BlockPos(
                    parameters.get("x").getAsInt(),
                    parameters.get("y").getAsInt(),
                    parameters.get("z").getAsInt());
        } else if (parameters.has("block")) {
            String blockId = parameters.get("block").getAsString().toLowerCase();
            Block targetBlock = resolveKnownBlock(blockId);
            if (targetBlock == null) {
                notifyNearbyPlayers(server, villager, "[LLM NPC] Unsupported mine block: " + blockId);
                return false;
            }
            targetPos = findNearestBlock(level, villager.blockPosition(), targetBlock, 6);
            if (targetPos == null) {
                notifyNearbyPlayers(server, villager, "[LLM NPC] Could not find nearby " + blockId + " to mine.");
                return false;
            }
        } else {
            return false;
        }

        boolean moving = villager.getNavigation().moveTo(
                targetPos.getX() + 0.5,
                targetPos.getY() + 0.5,
                targetPos.getZ() + 0.5,
                0.9);
        if (villager.distanceToSqr(targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5) > 4.0) {
            return moving;
        }

        boolean removed = level.removeBlock(targetPos, false);
        if (removed) {
            notifyNearbyPlayers(server, villager, "[LLM NPC] Mined block at " + targetPos.getX() + ", "
                    + targetPos.getY() + ", " + targetPos.getZ() + ".");
        }
        return removed;
    }

    private boolean startMineToChestTask(MinecraftServer server, UUID npcId, Villager villager, JsonObject parameters) {
        if (!(villager.level() instanceof ServerLevel level)) {
            return false;
        }
        if (!parameters.has("block")) {
            return false;
        }

        String blockId = parameters.get("block").getAsString().toLowerCase();
        int count = parameters.has("count") ? Math.max(1, parameters.get("count").getAsInt()) : 1;
        Block block = resolveKnownBlock(blockId);
        Item minedItem = resolveKnownItem(blockId);
        if (block == null || minedItem == null) {
            notifyNearbyPlayers(server, villager, "[LLM NPC] Unsupported mine-to-chest block: " + blockId);
            return false;
        }

        BlockPos chestPos = findNearestChest(server, level, villager.blockPosition(), CHEST_SCAN_RADIUS);
        if (chestPos == null) {
            notifyNearbyPlayers(server, villager, "[LLM NPC] I could not find a nearby chest for storage.");
            return false;
        }

        MineToChestTask task = new MineToChestTask(block, minedItem, blockId, count, chestPos);
        activeMineToChest.put(npcId, task);
        speakAsNpc(server, villager, villager.getName().getString(),
                "On it! I'll mine " + count + "x " + blockId + " and store them in the chest.");
        return true;
    }

    private boolean startMineToPlayerTask(MinecraftServer server, UUID npcId, Villager villager,
            JsonObject parameters) {
        if (!(villager.level() instanceof ServerLevel)) {
            return false;
        }
        MineToPlayerTask existing = activeMineToPlayer.get(npcId);
        if (existing != null && existing.phase != MineToPlayerPhase.DONE) {
            notifyNearbyPlayers(server, villager,
                    "[LLM NPC] I am already mining " + existing.count + "x " + existing.blockId + " for delivery.");
            return true;
        }
        if (!parameters.has("block")) {
            return false;
        }

        String blockId = parameters.get("block").getAsString().toLowerCase();
        int count = parameters.has("count") ? Math.max(1, parameters.get("count").getAsInt()) : 1;
        Block block = resolveKnownBlock(blockId);
        Item minedItem = resolveKnownItem(blockId);
        if (block == null || minedItem == null) {
            notifyNearbyPlayers(server, villager, "[LLM NPC] Unsupported mine-to-player block: " + blockId);
            return false;
        }
        ServerPlayer targetPlayer = nearestPlayer(server, villager);
        if (targetPlayer == null) {
            notifyNearbyPlayers(server, villager, "[LLM NPC] I cannot find a player to deliver mined blocks.");
            return false;
        }

        MineToPlayerTask task = new MineToPlayerTask(block, minedItem, blockId, count, targetPlayer.getUUID());
        activeMineToPlayer.put(npcId, task);
        String startMsg = parseDialogueText(llmRouter.generate(
                promptFactory.mineTaskStartPrompt(villager.getName().getString(),
                        targetPlayer.getName().getString(), count, blockId)));
        speakAsNpc(server, villager, villager.getName().getString(),
                startMsg != null && !startMsg.isBlank() ? startMsg
                        : "I'll mine those blocks and bring them to you.");
        return true;
    }

    private boolean advanceMineToChestTask(MinecraftServer server, Villager villager, MineToChestTask task) {
        if (!(villager.level() instanceof ServerLevel level)) {
            return false;
        }

        return switch (task.phase) {
            case FIND_OR_MOVE_TO_BLOCK -> {
                if (task.targetPos == null || !level.getBlockState(task.targetPos).is(task.block)) {
                    task.targetPos = findNearestBlock(level, villager.blockPosition(), task.block, MINE_SCAN_RADIUS);
                }
                if (task.targetPos == null) {
                    notifyNearbyPlayers(server, villager, "[LLM NPC] Could not find more " + task.blockId + " nearby.");
                    yield false;
                }
                boolean moving = villager.getNavigation().moveTo(
                        task.targetPos.getX() + 0.5,
                        task.targetPos.getY() + 0.5,
                        task.targetPos.getZ() + 0.5,
                        0.9);
                double dist = villager.distanceToSqr(
                        task.targetPos.getX() + 0.5,
                        task.targetPos.getY() + 0.5,
                        task.targetPos.getZ() + 0.5);
                if (dist <= 4.0) {
                    task.phase = MineToChestPhase.MINE_TARGET_BLOCK;
                }
                yield moving || dist <= 4.0;
            }
            case MINE_TARGET_BLOCK -> {
                if (task.targetPos == null || !level.getBlockState(task.targetPos).is(task.block)) {
                    task.phase = MineToChestPhase.FIND_OR_MOVE_TO_BLOCK;
                    yield true;
                }
                boolean removed = level.removeBlock(task.targetPos, false);
                if (!removed) {
                    task.phase = MineToChestPhase.FIND_OR_MOVE_TO_BLOCK;
                    yield true;
                }
                if (task.heldStack.isEmpty()) {
                    task.heldStack = new ItemStack(task.item, 1);
                } else {
                    task.heldStack.grow(1);
                }
                task.minedCount += 1;
                task.targetPos = null;
                if (task.minedCount >= task.count) {
                    task.phase = MineToChestPhase.MOVE_TO_CHEST;
                } else {
                    task.phase = MineToChestPhase.FIND_OR_MOVE_TO_BLOCK;
                }
                yield true;
            }
            case MOVE_TO_CHEST -> {
                boolean moving = villager.getNavigation().moveTo(
                        task.chestPos.getX() + 0.5,
                        task.chestPos.getY() + 0.5,
                        task.chestPos.getZ() + 0.5,
                        0.85);
                double dist = villager.distanceToSqr(
                        task.chestPos.getX() + 0.5,
                        task.chestPos.getY() + 0.5,
                        task.chestPos.getZ() + 0.5);
                if (dist <= 3.0) {
                    task.phase = MineToChestPhase.DEPOSIT_TO_CHEST;
                }
                yield moving || dist <= 3.0;
            }
            case DEPOSIT_TO_CHEST -> {
                if (task.heldStack.isEmpty()) {
                    yield false;
                }
                ItemStack remaining = insertIntoChest(server, level, task.chestPos, task.heldStack);
                if (!remaining.isEmpty()) {
                    task.heldStack = remaining;
                    notifyNearbyPlayers(server, villager, "[LLM NPC] Chest is full. Could not store all mined blocks.");
                    yield false;
                }
                notifyNearbyPlayers(server, villager,
                        "[LLM NPC] Stored " + task.minedCount + "x " + task.blockId + " in chest.");
                task.phase = MineToChestPhase.DONE;
                task.heldStack = ItemStack.EMPTY;
                yield false;
            }
            case DONE -> false;
        };
    }

    private boolean advanceMineToPlayerTask(MinecraftServer server, Villager villager, MineToPlayerTask task) {
        if (!(villager.level() instanceof ServerLevel level)) {
            return false;
        }

        return switch (task.phase) {
            case FIND_OR_MOVE_TO_BLOCK -> {
                if (task.targetPos == null || !level.getBlockState(task.targetPos).is(task.block)) {
                    task.targetPos = findNearestBlock(level, villager.blockPosition(), task.block, task.searchRadius,
                            task.blockedTargets);
                }
                if (task.targetPos == null) {
                    if (task.searchRadius < 48) {
                        task.searchRadius = Math.min(48, task.searchRadius + 6);
                        yield true;
                    }
                    if (!task.heldStack.isEmpty()) {
                        notifyNearbyPlayers(server, villager, "[LLM NPC] Could only mine " + task.minedCount + "x "
                                + task.blockId + ". Delivering what I have.");
                        task.phase = MineToPlayerPhase.MOVE_TO_PLAYER;
                        yield true;
                    }
                    notifyNearbyPlayers(server, villager, "[LLM NPC] Could not find more " + task.blockId + " nearby.");
                    yield false;
                }
                boolean moving = villager.getNavigation().moveTo(
                        task.targetPos.getX() + 0.5,
                        task.targetPos.getY() + 0.5,
                        task.targetPos.getZ() + 0.5,
                        0.9);
                double dist = villager.distanceToSqr(
                        task.targetPos.getX() + 0.5,
                        task.targetPos.getY() + 0.5,
                        task.targetPos.getZ() + 0.5);
                if (dist <= 4.0) {
                    task.phase = MineToPlayerPhase.MINE_TARGET_BLOCK;
                    task.noPathAttempts = 0;
                }
                if (!moving && dist > 4.0) {
                    if (task.targetPos != null) {
                        task.blockedTargets.add(task.targetPos.immutable());
                        if (task.blockedTargets.size() > 96) {
                            task.blockedTargets.remove(0);
                        }
                    }
                    task.targetPos = null;
                    task.noPathAttempts += 1;
                    if (task.noPathAttempts > 200) {
                        notifyNearbyPlayers(server, villager,
                                "[LLM NPC] I could not path to enough " + task.blockId + " to finish the order.");
                        yield false;
                    }
                    if (task.searchRadius < 48) {
                        task.searchRadius = Math.min(48, task.searchRadius + 2);
                    }
                    yield true;
                }
                yield true;
            }
            case MINE_TARGET_BLOCK -> {
                if (task.targetPos == null || !level.getBlockState(task.targetPos).is(task.block)) {
                    task.phase = MineToPlayerPhase.FIND_OR_MOVE_TO_BLOCK;
                    yield true;
                }
                boolean removed = level.removeBlock(task.targetPos, false);
                if (!removed) {
                    task.phase = MineToPlayerPhase.FIND_OR_MOVE_TO_BLOCK;
                    yield true;
                }
                if (task.heldStack.isEmpty()) {
                    task.heldStack = new ItemStack(task.item, 1);
                } else {
                    task.heldStack.grow(1);
                }
                task.minedCount += 1;
                task.targetPos = null;
                if (task.minedCount % 10 == 0 && task.minedCount < task.count) {
                    notifyNearbyPlayers(server, villager, "[LLM NPC] Progress: mined " + task.minedCount + "/"
                            + task.count + " " + task.blockId + ".");
                }
                if (!task.heldStack.isEmpty() && task.heldStack.getCount() >= 32 && task.minedCount < task.count) {
                    task.phase = MineToPlayerPhase.MOVE_TO_PLAYER;
                    yield true;
                }
                if (task.minedCount >= task.count) {
                    task.phase = MineToPlayerPhase.MOVE_TO_PLAYER;
                } else {
                    task.phase = MineToPlayerPhase.FIND_OR_MOVE_TO_BLOCK;
                }
                yield true;
            }
            case MOVE_TO_PLAYER -> {
                ServerPlayer player = server.getPlayerList().getPlayer(task.targetPlayerId);
                if (player == null || player.level() != villager.level()) {
                    notifyNearbyPlayers(server, villager, "[LLM NPC] Target player unavailable for delivery.");
                    if (!task.heldStack.isEmpty()) {
                        Containers.dropItemStack(
                                villager.level(),
                                villager.getX(),
                                villager.getY() + 1.0,
                                villager.getZ(),
                                task.heldStack.copy());
                        task.heldStack = ItemStack.EMPTY;
                    }
                    yield false;
                }
                boolean moving = villager.getNavigation().moveTo(player.getX(), player.getY(), player.getZ(), 0.85);
                double distance = villager.distanceToSqr(player);
                if (distance <= 9.0 || !moving) {
                    // If navigation fails (no path), still attempt direct inventory handoff.
                    task.phase = MineToPlayerPhase.GIVE_TO_PLAYER;
                }
                yield true;
            }
            case GIVE_TO_PLAYER -> {
                ServerPlayer player = server.getPlayerList().getPlayer(task.targetPlayerId);
                if (task.heldStack.isEmpty()) {
                    yield false;
                }
                if (player == null) {
                    Containers.dropItemStack(
                            villager.level(),
                            villager.getX(),
                            villager.getY() + 1.0,
                            villager.getZ(),
                            task.heldStack.copy());
                    task.heldStack = ItemStack.EMPTY;
                    notifyNearbyPlayers(server, villager,
                            "[LLM NPC] Target player unavailable. Dropped mined items nearby.");
                    yield false;
                }
                ItemStack remaining = insertIntoPlayerInventory(player, task.heldStack.copy());
                int deliveredCount = task.heldStack.getCount() - remaining.getCount();
                if (deliveredCount > 0) {
                    String doneMsg = parseDialogueText(llmRouter.generate(
                            promptFactory.mineTaskCompletePrompt(villager.getName().getString(),
                                    player.getName().getString(), deliveredCount, task.blockId)));
                    speakAsNpc(server, villager, villager.getName().getString(),
                            doneMsg != null && !doneMsg.isBlank() ? doneMsg
                                    : "I've put the blocks in your inventory.");
                }
                if (!remaining.isEmpty()) {
                    Containers.dropItemStack(
                            player.level(),
                            player.getX(),
                            player.getY() + 1.0,
                            player.getZ(),
                            remaining);
                    notifyNearbyPlayers(server, villager, "[LLM NPC] Inventory full. Dropped remaining "
                            + remaining.getCount() + "x " + task.blockId + ".");
                }
                if (task.minedCount < task.count) {
                    task.noPathAttempts = 0;
                    task.phase = MineToPlayerPhase.FIND_OR_MOVE_TO_BLOCK;
                } else {
                    task.phase = MineToPlayerPhase.DONE;
                }
                task.heldStack = ItemStack.EMPTY;
                yield task.phase != MineToPlayerPhase.DONE;
            }
            case DONE -> false;
        };
    }

    private boolean advanceSearchTask(MinecraftServer server, Villager villager, String fallbackName, SearchTask task) {
        if (!(villager.level() instanceof ServerLevel level)) {
            return false;
        }
        ServerPlayer requester = server.getPlayerList().getPlayer(task.requesterId);
        if (requester == null || requester.level() != villager.level()) {
            speakSearchStatusWithLlm(
                    server,
                    villager,
                    fallbackName,
                    "player",
                    "search_cancelled",
                    "target=" + task.targetLabel,
                    "Search cancelled because the requester is unavailable.");
            suppressTradeGreeting(villager.getUUID(), task.requesterId, SEARCH_TRADE_SUPPRESS_MILLIS);
            return false;
        }

        return switch (task.phase) {
            case FIND_TARGET -> {
                task.scanTickCounter += 1;
                if (task.patrolTarget == null
                        || villager.getNavigation().isDone()
                        || villager.distanceToSqr(
                                task.patrolTarget.getX() + 0.5,
                                task.patrolTarget.getY() + 0.5,
                                task.patrolTarget.getZ() + 0.5) <= 6.25
                        || task.scanTickCounter - task.lastPatrolAssignTick >= SEARCH_SCAN_INTERVAL_TICKS) {
                    task.patrolTarget = pickSearchPatrolTarget(level, villager.blockPosition(), villager,
                            task.searchRadius);
                    task.lastPatrolAssignTick = task.scanTickCounter;
                }
                if (task.patrolTarget != null) {
                    double distanceToPatrol = villager.distanceToSqr(
                            task.patrolTarget.getX() + 0.5,
                            task.patrolTarget.getY() + 0.5,
                            task.patrolTarget.getZ() + 0.5);
                    boolean moving = villager.getNavigation().moveTo(
                            task.patrolTarget.getX() + 0.5,
                            task.patrolTarget.getY() + 0.5,
                            task.patrolTarget.getZ() + 0.5,
                            0.9);
                    if (!moving) {
                        task.patrolTarget = null;
                        task.patrolNoProgressTicks = 0;
                        task.lastPatrolDistance = Double.MAX_VALUE;
                    } else {
                        if (distanceToPatrol + 0.25 < task.lastPatrolDistance) {
                            task.patrolNoProgressTicks = 0;
                        } else {
                            task.patrolNoProgressTicks += 1;
                        }
                        task.lastPatrolDistance = distanceToPatrol;
                        if (task.patrolNoProgressTicks >= SEARCH_PATROL_STALL_TICKS) {
                            task.patrolTarget = null;
                            task.patrolNoProgressTicks = 0;
                            task.lastPatrolDistance = Double.MAX_VALUE;
                        }
                    }
                }
                if (task.scanTickCounter % SEARCH_SCAN_INTERVAL_TICKS != 0) {
                    yield true;
                }
                Entity target = findNearestSearchTarget(level, villager, task);
                if (target != null) {
                    task.targetEntityId = target.getUUID();
                    task.lastKnownTargetPos = target.blockPosition();
                    task.phase = SearchPhase.MOVE_TO_TARGET;
                    task.patrolTarget = null;
                    task.patrolNoProgressTicks = 0;
                    task.lastPatrolDistance = Double.MAX_VALUE;
                    BlockPos pos = target.blockPosition();
                    speakSearchStatusWithLlm(
                            server,
                            villager,
                            fallbackName,
                            requester.getName().getString(),
                            "target_found",
                            "target=" + task.targetLabel + "; x=" + pos.getX() + "; y=" + pos.getY() + "; z="
                                    + pos.getZ(),
                            "I found " + task.targetLabel + " near " + pos.getX() + ", " + pos.getY() + ", "
                                    + pos.getZ() + ".");
                    yield true;
                }
                task.failedScans += 1;
                if (task.searchRadius < SEARCH_MAX_RADIUS) {
                    task.searchRadius = Math.min(SEARCH_MAX_RADIUS, task.searchRadius + 8);
                    if (task.searchRadius != task.lastAnnouncedRadius && shouldEmitSearchStatus(task)) {
                        task.lastAnnouncedRadius = task.searchRadius;
                        speakSearchStatusWithLlm(
                                server,
                                villager,
                                fallbackName,
                                requester.getName().getString(),
                                "search_expand",
                                "target=" + task.targetLabel + "; radius=" + task.searchRadius,
                                "Expanding search radius to " + task.searchRadius + " blocks.");
                    }
                    yield true;
                }
                if (task.failedScans >= 20) {
                    speakSearchStatusWithLlm(
                            server,
                            villager,
                            fallbackName,
                            requester.getName().getString(),
                            "search_failed",
                            "target=" + task.targetLabel + "; radius=" + task.searchRadius,
                            "I could not find " + task.targetLabel + " nearby.");
                    suppressTradeGreeting(villager.getUUID(), task.requesterId, SEARCH_TRADE_SUPPRESS_MILLIS);
                    yield false;
                }
                yield true;
            }
            case MOVE_TO_TARGET -> {
                Entity target = level.getEntity(task.targetEntityId);
                if (target == null || !target.isAlive() || !matchesSearchTarget(target, task)) {
                    if (task.lastKnownTargetPos != null) {
                        task.phase = SearchPhase.RETURN_TO_PLAYER;
                    } else {
                        task.targetEntityId = null;
                        task.phase = SearchPhase.FIND_TARGET;
                        task.failedScans = 0;
                        task.patrolTarget = null;
                        task.patrolNoProgressTicks = 0;
                        task.lastPatrolDistance = Double.MAX_VALUE;
                    }
                    yield true;
                }
                task.lastKnownTargetPos = target.blockPosition();
                villager.getNavigation().moveTo(target.getX(), target.getY(), target.getZ(), 0.9);
                if (villager.distanceToSqr(target) <= 9.0) {
                    task.phase = SearchPhase.RETURN_TO_PLAYER;
                }
                yield true;
            }
            case RETURN_TO_PLAYER -> {
                villager.getNavigation().moveTo(requester.getX(), requester.getY(), requester.getZ(), 0.9);
                if (villager.distanceToSqr(requester) <= 16.0) {
                    BlockPos pos = task.lastKnownTargetPos;
                    String locStr = pos != null
                            ? pos.getX() + ", " + pos.getY() + ", " + pos.getZ()
                            : "nearby";
                    speakSearchStatusWithLlm(
                            server, villager, fallbackName, requester.getName().getString(),
                            "search_complete",
                            "target=" + task.targetLabel + "; x=" + (pos != null ? pos.getX() : "?")
                                    + "; y=" + (pos != null ? pos.getY() : "?")
                                    + "; z=" + (pos != null ? pos.getZ() : "?"),
                            "I found " + task.targetLabel + "! It was at " + locStr + ".");
                    recordTaskSummary(villager.getUUID(),
                            "Searched for " + task.targetLabel + " and found it at " + locStr
                            + " for " + requester.getName().getString() + ".");
                    suppressTradeGreeting(villager.getUUID(), task.requesterId, SEARCH_TRADE_SUPPRESS_MILLIS);
                    yield false;
                }
                yield true;
            }
            case BEACON -> {
                Entity target = level.getEntity(task.targetEntityId);
                if (target == null || !target.isAlive() || !matchesSearchTarget(target, task)) {
                    // Already found and stood next to target — count as success even if it wandered off
                    speakSearchStatusWithLlm(
                            server,
                            villager,
                            fallbackName,
                            requester.getName().getString(),
                            "search_complete",
                            "target=" + task.targetLabel,
                            "Search complete. " + task.targetLabel + " moved away.");
                    suppressTradeGreeting(villager.getUUID(), task.requesterId, SEARCH_TRADE_SUPPRESS_MILLIS);
                    yield false;
                }
                villager.getNavigation().moveTo(target.getX(), target.getY(), target.getZ(), 0.75);
                task.beaconTicksRemaining -= 1;
                if (task.beaconTicksRemaining == 200) {
                    BlockPos pos = target.blockPosition();
                    speakSearchStatusWithLlm(
                            server,
                            villager,
                            fallbackName,
                            requester.getName().getString(),
                            "beacon_update",
                            "target=" + task.targetLabel + "; x=" + pos.getX() + "; y=" + pos.getY() + "; z="
                                    + pos.getZ(),
                            "I am still here with " + task.targetLabel + " at " + pos.getX() + ", " + pos.getY() + ", "
                                    + pos.getZ() + ".");
                }
                if (task.beaconTicksRemaining <= 0) {
                    speakSearchStatusWithLlm(
                            server,
                            villager,
                            fallbackName,
                            requester.getName().getString(),
                            "search_complete",
                            "target=" + task.targetLabel,
                            "Search complete.");
                    suppressTradeGreeting(villager.getUUID(), task.requesterId, SEARCH_TRADE_SUPPRESS_MILLIS);
                    yield false;
                }
                yield true;
            }
            case DONE -> false;
        };
    }

    private boolean shouldEmitSearchStatus(SearchTask task) {
        long now = System.currentTimeMillis();
        if (now < task.nextStatusAtMillis) {
            return false;
        }
        task.nextStatusAtMillis = now + SEARCH_STATUS_COOLDOWN_MILLIS;
        return true;
    }

    private void suppressTradeGreeting(UUID npcId, UUID playerId, long durationMillis) {
        if (npcId == null || playerId == null || durationMillis <= 0L) {
            return;
        }
        TradeSessionKey key = new TradeSessionKey(npcId, playerId);
        long until = System.currentTimeMillis() + durationMillis;
        long current = nextTradeGreetingAt.getOrDefault(key, 0L);
        if (until > current) {
            nextTradeGreetingAt.put(key, until);
        }
    }

    private Entity findNearestSearchTarget(ServerLevel level, Villager villager, SearchTask task) {
        List<Entity> nearby = level.getEntities(villager, villager.getBoundingBox().inflate(task.searchRadius));
        Entity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : nearby) {
            if (entity == villager || !entity.isAlive() || !matchesSearchTarget(entity, task)) {
                continue;
            }
            double distance = villager.distanceToSqr(entity);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entity;
            }
        }
        return best;
    }

    private BlockPos pickSearchPatrolTarget(ServerLevel level, BlockPos anchor, Villager villager, int radius) {
        int minOffset = Math.max(6, radius / 3);
        for (int i = 0; i < 20; i++) {
            int dx = villager.getRandom().nextInt(radius * 2 + 1) - radius;
            int dz = villager.getRandom().nextInt(radius * 2 + 1) - radius;
            if (Math.abs(dx) + Math.abs(dz) < minOffset) {
                continue;
            }
            BlockPos probe = anchor.offset(dx, 0, dz);
            BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, probe);
            BlockPos candidate = surface.above();
            if (!level.getBlockState(candidate).isAir() || !level.getBlockState(candidate.above()).isAir()) {
                continue;
            }
            return candidate.immutable();
        }
        return anchor.above();
    }

    private boolean matchesSearchTarget(Entity entity, SearchTask task) {
        String entityTypeId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        if (!task.entityTypeId.equals(entityTypeId)) {
            return false;
        }
        if (task.requirePinkSheep) {
            if (!(entity instanceof Sheep sheep)) {
                return false;
            }
            return sheep.getColor() == DyeColor.PINK;
        }
        return true;
    }

    private String buildEnvironmentalSignature(WorldSnapshot snapshot) {
        if (snapshot == null) return "";
        StringBuilder sb = new StringBuilder();
        WorldSnapshot.EnvironmentContext env = snapshot.environment();
        if (env != null) {
            sb.append(env.biome()).append('|').append(env.threatLevel()).append('|').append(env.isNight());
        }
        if (snapshot.nearbyEntities() != null && !snapshot.nearbyEntities().isEmpty()) {
            for (WorldSnapshot.SeenEntity e : snapshot.nearbyEntities()) {
                sb.append('|').append(e.type()).append(':').append((int) Math.round(e.distance()));
            }
        }
        if (snapshot.nearbyBlockHistogram() != null && !snapshot.nearbyBlockHistogram().isEmpty()) {
            int i = 0;
            for (Map.Entry<String, Integer> entry : snapshot.nearbyBlockHistogram().entrySet()) {
                sb.append('|').append(entry.getKey()).append('=').append(entry.getValue());
                if (++i >= 5) break;
            }
        }
        return sb.toString();
    }

    private record EnvironmentalAdvice(String responseText, String severity, java.util.List<String> evidence) {}

    private EnvironmentalAdvice parseEnvironmentalAdvice(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}');
            if (start < 0 || end <= start) return null;
            JsonObject root = JsonParser.parseString(raw.substring(start, end + 1)).getAsJsonObject();
            String response = root.has("response_text") ? root.get("response_text").getAsString() : "";
            // Reject if response_text is suspiciously long (LLM echoed the prompt)
            if (response.length() > 300) return null;
            String severity = root.has("severity") ? root.get("severity").getAsString() : "low";
            java.util.List<String> evidence = new java.util.ArrayList<>();
            if (root.has("evidence") && root.get("evidence").isJsonArray()) {
                for (var el : root.getAsJsonArray("evidence")) {
                    try { evidence.add(el.getAsString()); } catch (Exception ignored) {}
                }
            }
            return new EnvironmentalAdvice(response, severity, evidence);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String buildEvidenceSuffix(java.util.List<String> evidence) {
        if (evidence == null || evidence.isEmpty()) return "";
        try {
            return " (evidence: " + String.join(", ", evidence) + ")";
        } catch (Exception ignored) { return ""; }
    }

    private int threatOrdinal(String t) {
        if (t == null) return 0;
        String low = t.toLowerCase(Locale.ROOT);
        return switch (low) {
            case "high" -> 2;
            case "medium" -> 1;
            default -> 0;
        };
    }

    private boolean hasActiveWork(UUID npcId) {
        if (activeMineToPlayer.containsKey(npcId)
                || activeMineToChest.containsKey(npcId)
                || activeDeliveries.containsKey(npcId)
                || activeSearchTasks.containsKey(npcId)
                || activeScoutTasks.containsKey(npcId)
                || activeScenarioTasks.containsKey(npcId)) {
            return true;
        }
        List<JsonObject> queued = actionOutbox.get(npcId);
        return queued != null && !queued.isEmpty();
    }

    private boolean advanceDeliveryTask(MinecraftServer server, Villager villager, DeliveryTask task) {
        if (!(villager.level() instanceof ServerLevel level)) {
            return false;
        }

        return switch (task.phase) {
            case MOVE_TO_CHEST -> {
                boolean moving = villager.getNavigation().moveTo(
                        task.chestPos.getX() + 0.5,
                        task.chestPos.getY() + 0.5,
                        task.chestPos.getZ() + 0.5,
                        0.85);
                double dist = villager.distanceToSqr(
                        task.chestPos.getX() + 0.5,
                        task.chestPos.getY() + 0.5,
                        task.chestPos.getZ() + 0.5);
                if (dist <= 3.0) {
                    task.phase = DeliveryPhase.WITHDRAW_FROM_CHEST;
                }
                yield moving || dist <= 3.0;
            }
            case WITHDRAW_FROM_CHEST -> {
                ItemStack collected = withdrawFromChest(level, task.chestPos, task.item, task.count);
                if (collected.isEmpty()) {
                    notifyNearbyPlayers(server, villager,
                            "[LLM NPC] Could not retrieve " + task.itemId + " from chest.");
                    yield false;
                }
                task.heldStack = collected;
                task.phase = DeliveryPhase.MOVE_TO_PLAYER;
                yield true;
            }
            case MOVE_TO_PLAYER -> {
                ServerPlayer player = server.getPlayerList().getPlayer(task.targetPlayerId);
                if (player == null || player.level() != villager.level()) {
                    notifyNearbyPlayers(server, villager, "[LLM NPC] Target player unavailable for delivery.");
                    yield false;
                }
                boolean moving = villager.getNavigation().moveTo(player.getX(), player.getY(), player.getZ(), 0.85);
                if (villager.distanceToSqr(player) <= 9.0) {
                    task.phase = DeliveryPhase.DROP_TO_PLAYER;
                }
                yield moving || villager.distanceToSqr(player) <= 9.0;
            }
            case DROP_TO_PLAYER -> {
                ServerPlayer player = server.getPlayerList().getPlayer(task.targetPlayerId);
                if (player == null || task.heldStack.isEmpty()) {
                    yield false;
                }
                Containers.dropItemStack(
                        player.level(),
                        player.getX(),
                        player.getY() + 1.0,
                        player.getZ(),
                        task.heldStack.copy());
                notifyNearbyPlayers(server, villager,
                        "[LLM NPC] Delivered " + task.heldStack.getCount() + "x " + task.itemId + ".");
                task.phase = DeliveryPhase.DONE;
                task.heldStack = ItemStack.EMPTY;
                yield false;
            }
            case DONE -> false;
        };
    }

    private ItemStack withdrawFromChest(ServerLevel level, BlockPos chestPos, Item item, int neededCount) {
        BlockEntity blockEntity = level.getBlockEntity(chestPos);
        if (!(blockEntity instanceof ChestBlockEntity chest)) {
            return ItemStack.EMPTY;
        }

        int remaining = neededCount;
        ItemStack collected = ItemStack.EMPTY;
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            ItemStack current = chest.getItem(slot);
            if (!current.is(item)) {
                continue;
            }
            int take = Math.min(remaining, current.getCount());
            ItemStack removed = chest.removeItem(slot, take);
            if (removed.isEmpty()) {
                continue;
            }
            if (collected.isEmpty()) {
                collected = removed.copy();
            } else {
                collected.grow(removed.getCount());
            }
            remaining -= removed.getCount();
            if (remaining <= 0) {
                break;
            }
        }
        chest.setChanged();
        return collected;
    }

    private BlockPos findChestWithItem(ServerLevel level, BlockPos center, Item item, int radius, int neededCount) {
        BlockPos bestPos = null;
        double bestDistance = Double.MAX_VALUE;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -16; dy <= 16; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    BlockEntity blockEntity = level.getBlockEntity(pos);
                    if (!(blockEntity instanceof ChestBlockEntity chest)) {
                        continue;
                    }
                    int available = 0;
                    for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                        ItemStack stack = chest.getItem(slot);
                        if (stack.is(item)) {
                            available += stack.getCount();
                        }
                    }
                    if (available < neededCount) {
                        continue;
                    }
                    double distance = squaredDistance(center, pos);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        bestPos = pos.immutable();
                    }
                }
            }
        }
        return bestPos;
    }

    private BlockPos findNearestChest(MinecraftServer server, ServerLevel level, BlockPos center, int radius) {
        if (!server.isSameThread()) {
            try {
                return server.submit(() -> findNearestChest(server, level, center, radius)).get();
            } catch (Exception e) {
                logger.warn("[CHEST-SCAN] Failed to dispatch findNearestChest to main thread", e);
                return null;
            }
        }
        BlockPos bestPos = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -16; dy <= 16; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    BlockEntity blockEntity = level.getBlockEntity(pos);
                    if (!(blockEntity instanceof ChestBlockEntity)) {
                        continue;
                    }
                    double distance = squaredDistance(center, pos);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        bestPos = pos.immutable();
                    }
                }
            }
        }
        return bestPos;
    }

    private ItemStack insertIntoChest(MinecraftServer server, ServerLevel level, BlockPos chestPos, ItemStack stack) {
        if (!server.isSameThread()) {
            try {
                return server.submit(() -> insertIntoChest(server, level, chestPos, stack)).get();
            } catch (Exception e) {
                logger.warn("[CHEST-SCAN] Failed to dispatch insertIntoChest to main thread", e);
                return stack;
            }
        }
        BlockEntity blockEntity = level.getBlockEntity(chestPos);
        if (!(blockEntity instanceof ChestBlockEntity chest)) {
            return stack;
        }

        ItemStack remaining = stack.copy();
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            ItemStack current = chest.getItem(slot);
            if (current.isEmpty()) {
                chest.setItem(slot, remaining.copy());
                remaining = ItemStack.EMPTY;
                break;
            }
            if (!current.is(remaining.getItem())) {
                continue;
            }
            int max = current.getMaxStackSize();
            int free = max - current.getCount();
            if (free <= 0) {
                continue;
            }
            int move = Math.min(free, remaining.getCount());
            current.grow(move);
            remaining.shrink(move);
            chest.setItem(slot, current);
            if (remaining.isEmpty()) {
                break;
            }
        }
        chest.setChanged();
        return remaining;
    }

    private ItemStack insertIntoPlayerInventory(ServerPlayer player, ItemStack stack) {
        ItemStack remaining = stack.copy();
        if (remaining.isEmpty()) {
            return ItemStack.EMPTY;
        }
        boolean accepted = player.getInventory().add(remaining);
        if (!accepted && !remaining.isEmpty()) {
            return remaining;
        }
        return remaining;
    }

    private BlockPos findNearestBlock(ServerLevel level, BlockPos center, Block block, int radius) {
        return findNearestBlock(level, center, block, radius, List.of());
    }

    private BlockPos findNearestBlock(ServerLevel level, BlockPos center, Block block, int radius,
            List<BlockPos> excluded) {
        BlockPos bestPos = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -16; dy <= 16; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (!level.getBlockState(pos).is(block)) {
                        continue;
                    }
                    if (!excluded.isEmpty() && excluded.contains(pos)) {
                        continue;
                    }
                    double distance = squaredDistance(center, pos);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        bestPos = pos.immutable();
                    }
                }
            }
        }
        return bestPos;
    }

    private String normalizeMinecraftId(String id) {
        if (id == null || id.isBlank()) return null;
        String trimmed = id.trim().replace(' ', '_').toLowerCase(Locale.ROOT);
        return trimmed.contains(":") ? trimmed : "minecraft:" + trimmed;
    }

    private Item resolveKnownItem(String itemId) {
        String normalized = normalizeMinecraftId(itemId);
        if (normalized == null) return null;
        try {
            Identifier key = Identifier.parse(normalized);
            return BuiltInRegistries.ITEM.get(key).map(ref -> ref.value()).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private Block resolveKnownBlock(String blockId) {
        String normalized = normalizeMinecraftId(blockId);
        if (normalized == null) return null;
        try {
            Identifier key = Identifier.parse(normalized);
            return BuiltInRegistries.BLOCK.get(key).map(ref -> ref.value()).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private SearchRequest parseSearchRequestWithLlm(String instruction, MemoryContext memory) {
        String prompt = promptFactory.searchRequestPrompt(instruction, memory);
        String raw = llmRouter.generate(prompt);
        if (raw == null || raw.isBlank()) return null;
        try {
            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}');
            if (start < 0 || end <= start) return null;
            JsonObject root = JsonParser.parseString(raw.substring(start, end + 1)).getAsJsonObject();
            boolean isSearch = root.has("is_search") && root.get("is_search").getAsBoolean();
            if (!isSearch) return null;
            String label = root.has("entity_label") ? root.get("entity_label").getAsString() : "entity";
            String entityId = root.has("entity_id") ? root.get("entity_id").getAsString() : null;
            if (entityId == null || entityId.isBlank() || !entityId.startsWith("minecraft:")) return null;
            boolean requirePink = label != null && label.toLowerCase(Locale.ROOT).contains("pink")
                    && entityId.equals("minecraft:sheep");
            logger.info("[SEARCH] parsed instruction='{}' entity_id={} label={}", instruction, entityId, label);
            return new SearchRequest(label, entityId, requirePink);
        } catch (Exception e) {
            logger.warn("[SEARCH] failed to parse LLM response: {}", raw);
            return null;
        }
    }


    private Item recipeTargetItem(String lower, String instruction) {
        String targetPhrase = extractRecipeTargetPhrase(lower);
        Item resolved = resolveItemFromRecipePhrase(targetPhrase);
        if (resolved != null) {
            return resolved;
        }
        return resolveItemFromRecipePhrase(instruction == null ? "" : instruction.toLowerCase(Locale.ROOT));
    }

    private String extractRecipeTargetPhrase(String lower) {
        if (lower == null || lower.isBlank()) {
            return "";
        }
        String normalized = lower.replace('?', ' ').replace('.', ' ').replace(',', ' ').trim();
        String[] anchors = new String[] {
                "what do i need to make ",
                "what do i need for ",
                "how do i make ",
                "how do i craft ",
                "recipe for ",
                "craft ",
                "make "
        };
        for (String anchor : anchors) {
            int index = normalized.indexOf(anchor);
            if (index < 0) {
                continue;
            }
            String phrase = normalized.substring(index + anchor.length()).trim();
            while (phrase.startsWith("a ") || phrase.startsWith("an ") || phrase.startsWith("the ")) {
                if (phrase.startsWith("a ")) {
                    phrase = phrase.substring(2).trim();
                } else if (phrase.startsWith("an ")) {
                    phrase = phrase.substring(3).trim();
                } else {
                    phrase = phrase.substring(4).trim();
                }
            }
            return phrase;
        }
        return normalized;
    }

    private Item resolveItemFromRecipePhrase(String phrase) {
        if (phrase == null || phrase.isBlank()) {
            return null;
        }
        String normalized = phrase.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9:_\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.isBlank()) {
            return null;
        }
        String candidatePath = normalized.replace(' ', '_');
        String singularCandidatePath = candidatePath.endsWith("s")
                ? candidatePath.substring(0, candidatePath.length() - 1)
                : candidatePath;

        Item best = null;
        int bestScore = -1;
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR) {
                continue;
            }
            String itemId = BuiltInRegistries.ITEM.getKey(item).toString();
            String path = itemId.replace("minecraft:", "");
            String label = path.replace('_', ' ');
            int score = -1;
            if (normalized.equals(itemId) || normalized.equals(path)
                    || candidatePath.equals(path) || singularCandidatePath.equals(path)) {
                score = 10_000 + path.length();
            } else if (normalized.equals(label)
                    || (normalized.endsWith("s") && normalized.substring(0, normalized.length() - 1).equals(label))) {
                score = 9_000 + label.length();
            } else if (normalized.contains(label)) {
                score = 1_000 + label.length();
            } else if (label.contains(normalized) && normalized.length() >= 4) {
                score = 100 + normalized.length();
            }
            if (score > bestScore) {
                bestScore = score;
                best = item;
            }
        }
        return bestScore > 0 ? best : null;
    }

    private RecipeSummary summarizeRecipeFromGame(MinecraftServer server, Item outputItem) {
        Object recipe = findRecipeForOutput(server, outputItem);
        if (recipe == null) {
            return fallbackRecipeSummary(outputItem);
        }
        List<RecipeIngredientNeed> needs = extractRecipeIngredients(recipe);
        if (needs.isEmpty()) {
            return fallbackRecipeSummary(outputItem);
        }
        List<String> parts = new ArrayList<>();
        for (RecipeIngredientNeed need : needs) {
            parts.add(need.count() + " " + need.readableName());
        }
        return new RecipeSummary(String.join(" and ", parts), needs);
    }

    private RecipeSummary fallbackRecipeSummary(Item outputItem) {
        if (outputItem == Items.DIAMOND_PICKAXE) {
            return new RecipeSummary(
                    "3 diamonds and 2 sticks",
                    List.of(
                            new RecipeIngredientNeed(Items.DIAMOND, "minecraft:diamond", "diamond", 3),
                            new RecipeIngredientNeed(Items.STICK, "minecraft:stick", "stick", 2)));
        }
        if (outputItem == Items.IRON_PICKAXE) {
            return new RecipeSummary(
                    "3 iron ingots and 2 sticks",
                    List.of(
                            new RecipeIngredientNeed(Items.IRON_INGOT, "minecraft:iron_ingot", "iron ingot", 3),
                            new RecipeIngredientNeed(Items.STICK, "minecraft:stick", "stick", 2)));
        }
        return new RecipeSummary("the required crafting ingredients", List.of());
    }

    private Object findRecipeForOutput(MinecraftServer server, Item outputItem) {
        try {
            Object recipeManager = server.getRecipeManager();
            Method getRecipes = recipeManager.getClass().getMethod("getRecipes");
            Object recipes = getRecipes.invoke(recipeManager);
            Iterable<?> iterable = null;
            if (recipes instanceof Map<?, ?> map) {
                iterable = map.values();
            } else if (recipes instanceof Iterable<?> iter) {
                iterable = iter;
            }
            if (iterable == null) {
                return null;
            }

            for (Object holder : iterable) {
                Object recipe = invokeIfPresent(holder, "value");
                if (recipe == null) {
                    recipe = holder;
                }
                ItemStack result = extractRecipeResult(server, recipe);
                if (result != null && !result.isEmpty() && result.is(outputItem)) {
                    return recipe;
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private List<RecipeIngredientNeed> extractRecipeIngredients(Object recipe) {
        Object ingredientsObj = invokeIfPresent(recipe, "getIngredients");
        if (!(ingredientsObj instanceof Iterable<?> ingredients)) {
            return List.of();
        }
        Map<Item, Integer> counts = new LinkedHashMap<>();
        for (Object ingredient : ingredients) {
            Object choicesObj = invokeIfPresent(ingredient, "getItems");
            if (choicesObj instanceof ItemStack[] choices) {
                Item chosen = null;
                for (ItemStack stack : choices) {
                    if (stack != null && !stack.isEmpty() && stack.getItem() != Items.AIR) {
                        chosen = stack.getItem();
                        break;
                    }
                }
                if (chosen != null) {
                    counts.merge(chosen, 1, Integer::sum);
                }
            }
        }
        List<RecipeIngredientNeed> needs = new ArrayList<>();
        for (Map.Entry<Item, Integer> entry : counts.entrySet()) {
            Item item = entry.getKey();
            String itemId = BuiltInRegistries.ITEM.getKey(item).toString();
            needs.add(new RecipeIngredientNeed(item, itemId, readableItemName(itemId), entry.getValue()));
        }
        needs.sort(Comparator.comparing(RecipeIngredientNeed::readableName));
        return needs;
    }

    private List<RecipeIngredientStatus> evaluateIngredientStatus(
            MinecraftServer server,
            ServerLevel level,
            BlockPos center,
            ServerPlayer player,
            List<RecipeIngredientNeed> needs) {
        List<RecipeIngredientStatus> statuses = new ArrayList<>();
        for (RecipeIngredientNeed need : needs) {
            int nearby = countItemInNearbyChests(server, level, center, need.item(), TRADE_SCAN_RADIUS);
            boolean playerHas = hasAtLeast(player, need.item(), need.count());
            statuses.add(new RecipeIngredientStatus(
                    need.item(),
                    need.itemId(),
                    need.readableName(),
                    need.count(),
                    nearby,
                    playerHas));
        }
        return statuses;
    }

    private String summarizeIngredientFacts(List<RecipeIngredientStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return "unknown";
        }
        List<String> entries = new ArrayList<>();
        for (RecipeIngredientStatus status : statuses) {
            entries.add(status.readableName()
                    + ":need=" + status.neededCount()
                    + ",nearby=" + status.nearbyCount()
                    + ",player_has=" + status.playerHasEnough());
        }
        return String.join("; ", entries);
    }

    private ItemStack extractRecipeResult(MinecraftServer server, Object recipe) {
        if (recipe == null) {
            return null;
        }
        Object registryAccess = invokeIfPresent(server, "registryAccess");
        Object result = registryAccess == null
                ? invokeIfPresent(recipe, "getResultItem")
                : invokeIfPresent(recipe, "getResultItem", registryAccess);
        if (!(result instanceof ItemStack stack) || stack.isEmpty()) {
            result = invokeIfPresent(recipe, "result");
            if (result instanceof ItemStack stack2 && !stack2.isEmpty()) {
                return stack2;
            }
            return null;
        }
        return stack;
    }

    private Object invokeIfPresent(Object target, String methodName, Object... args) {
        if (target == null) {
            return null;
        }
        Method[] methods = target.getClass().getMethods();
        for (Method method : methods) {
            if (!method.getName().equals(methodName) || method.getParameterCount() != args.length) {
                continue;
            }
            try {
                return method.invoke(target, args);
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private String readableItemName(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return "item";
        }
        String normalized = itemId.replace("minecraft:", "").replace('_', ' ');
        return normalized;
    }

    private void speakAsNpc(MinecraftServer server, Villager villager, String fallbackName, String text) {
        String npcName = villager.getName().getString();
        if (npcName == null || npcName.isBlank()) {
            npcName = fallbackName;
        }
        Component message = Component.literal("[" + npcName + "] " + text);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level() == villager.level() && player.distanceToSqr(villager) <= 160_000.0) {
                player.sendSystemMessage(message);
            }
        }
    }

    private int countItemInNearbyChests(MinecraftServer server, ServerLevel level, BlockPos center, Item item, int radius) {
        if (item == null) {
            return 0;
        }
        if (!server.isSameThread()) {
            try {
                return server.submit(() -> countItemInNearbyChests(server, level, center, item, radius)).get();
            } catch (Exception e) {
                logger.warn("[CHEST-SCAN] Failed to dispatch chest scan to main thread", e);
                return 0;
            }
        }
        int total = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -16; dy <= 16; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    BlockEntity blockEntity = level.getBlockEntity(pos);
                    if (!(blockEntity instanceof ChestBlockEntity chest)) {
                        continue;
                    }
                    for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                        ItemStack stack = chest.getItem(slot);
                        if (!stack.isEmpty() && stack.is(item)) {
                            total += stack.getCount();
                        }
                    }
                }
            }
        }
        return total;
    }

    private ItemStack withdrawFromNearbyChests(MinecraftServer server, ServerLevel level, BlockPos center, Item item,
            int radius, int neededCount) {
        if (!server.isSameThread()) {
            try {
                return server.submit(() -> withdrawFromNearbyChests(server, level, center, item, radius, neededCount))
                        .get();
            } catch (Exception e) {
                logger.warn("[CHEST-SCAN] Failed to dispatch chest withdraw to main thread", e);
                return ItemStack.EMPTY;
            }
        }
        int remaining = neededCount;
        ItemStack collected = ItemStack.EMPTY;
        for (int dx = -radius; dx <= radius && remaining > 0; dx++) {
            for (int dy = -16; dy <= 16 && remaining > 0; dy++) {
                for (int dz = -radius; dz <= radius && remaining > 0; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    BlockEntity blockEntity = level.getBlockEntity(pos);
                    if (!(blockEntity instanceof ChestBlockEntity chest)) {
                        continue;
                    }
                    for (int slot = 0; slot < chest.getContainerSize() && remaining > 0; slot++) {
                        ItemStack current = chest.getItem(slot);
                        if (!current.is(item)) {
                            continue;
                        }
                        int take = Math.min(remaining, current.getCount());
                        ItemStack removed = chest.removeItem(slot, take);
                        if (removed.isEmpty()) {
                            continue;
                        }
                        if (collected.isEmpty()) {
                            collected = removed.copy();
                        } else {
                            collected.grow(removed.getCount());
                        }
                        remaining -= removed.getCount();
                    }
                    chest.setChanged();
                }
            }
        }
        return collected;
    }

    private boolean hasAtLeast(ServerPlayer player, Item item, int requiredCount) {
        int total = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) {
                total += stack.getCount();
                if (total >= requiredCount) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean removeFromInventory(ServerPlayer player, Item item, int removeCount) {
        int remaining = removeCount;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.is(item) || stack.isEmpty()) {
                continue;
            }
            int take = Math.min(remaining, stack.getCount());
            ItemStack removed = player.getInventory().removeItem(slot, take);
            remaining -= removed.getCount();
            if (remaining <= 0) {
                return true;
            }
        }
        return false;
    }

    private ParsedTradeIntent classifyTradeIntentWithLlm(
            MinecraftServer server,
            Villager villager,
            ServerPlayer player,
            String instruction,
            TradeSession session,
            WorldSnapshot snapshot,
            MemoryContext memory,
            UUID npcId) {
        String prompt = promptFactory.tradeIntentClassifierPrompt(
                villager.getName().getString(),
                player.getName().getString(),
                instruction,
                session.activeOffer == null ? "" : summarizeOffer(session.activeOffer),
                tradeStockSummary(server, villager),
                snapshot,
                memory);
        tradeLlmInFlightByNpc.put(npcId, true);
        TradeIntentClassifierDraft classified;
        String rawClassified;
        try {
            rawClassified = llmRouter.generate(prompt);
            classified = tradeIntentClassifierParser.parse(rawClassified);
            if (classified.intent() == TradeIntentType.NONE) {
                String recoveryPrompt = promptFactory.tradeIntentRecoveryPrompt(
                        instruction,
                        session.activeOffer == null ? "" : summarizeOffer(session.activeOffer),
                        tradeStockSummary(server, villager),
                        session.lastRequestedItemId);
                String recoveryRaw = llmRouter.generate(recoveryPrompt);
                TradeIntentClassifierDraft recovered = tradeIntentClassifierParser.parse(recoveryRaw);
                rawClassified = recoveryRaw;
                if (recovered.intent() != TradeIntentType.NONE) {
                    classified = recovered;
                }
            }
        } finally {
            tradeLlmInFlightByNpc.put(npcId, false);
        }
        if (classified.intent() != TradeIntentType.NONE && classified.confidence() <= 0.0) {
            classified = new TradeIntentClassifierDraft(
                    classified.intent(),
                    classified.itemId(),
                    classified.quantity(),
                    classified.counterTotalPrice(),
                    0.7);
        }

        logger.info(
                "[TRADE-DEBUG] npc={} trade_intent_llm intent={} confidence={} item={} quantity={} counter={} active_offer_present={} raw={}",
                villager.getName().getString(),
                classified.intent().name().toLowerCase(Locale.ROOT),
                classified.confidence(),
                classified.itemId(),
                classified.quantity(),
                classified.counterTotalPrice(),
                session.activeOffer != null,
                rawClassified == null ? "" : rawClassified.replace('\n', ' ').replace('\r', ' '));
        if (TRADE_DEBUG_CHAT) {
            player.sendSystemMessage(Component.literal(String.format(
                    Locale.ROOT,
                    "[LLM NPC][DEBUG] trade-intent LLM: intent=%s confidence=%.2f item=%s qty=%d counter=%s",
                    classified.intent().name().toLowerCase(Locale.ROOT),
                    classified.confidence(),
                    classified.itemId() == null || classified.itemId().isBlank() ? "-" : classified.itemId(),
                    classified.quantity(),
                    classified.counterTotalPrice() == null ? "-" : classified.counterTotalPrice())));
        }
        if (classified.intent() == TradeIntentType.NONE) {
            logger.info("[TRADE-DEBUG] npc={} trade_intent_llm rejected: none intent", villager.getName().getString());
            return ParsedTradeIntent.none();
        }
        if (classified.confidence() < TRADE_INTENT_CONFIDENCE_THRESHOLD) {
            logger.info(
                    "[TRADE-DEBUG] npc={} trade_intent_llm low confidence {} < {}, proceeding because intent is non-none",
                    villager.getName().getString(),
                    classified.confidence(),
                    TRADE_INTENT_CONFIDENCE_THRESHOLD);
        }
        if (classified.intent() == TradeIntentType.ACCEPT_OFFER && session.activeOffer == null) {
            logger.info("[TRADE-DEBUG] npc={} trade_intent_llm rejected: accept_offer without active offer",
                    villager.getName().getString());
            return ParsedTradeIntent.none();
        }
        if (classified.intent() == TradeIntentType.COUNTER_OFFER && session.activeOffer == null) {
            String cItemId = classified.itemId();
            boolean cHasItem = cItemId != null && !cItemId.isBlank()
                    && !cItemId.equalsIgnoreCase("none") && !cItemId.equalsIgnoreCase("minecraft:none");
            if (!cHasItem) {
                logger.info("[TRADE-DEBUG] npc={} trade_intent_llm rejected: counter_offer without active offer or item",
                        villager.getName().getString());
                return ParsedTradeIntent.none();
            }
            // Has item — let COUNTER_OFFER through; handled downstream after draft is ready
        }
        String itemId = classified.itemId();
        if ((classified.intent() == TradeIntentType.REQUEST_OFFER
                || classified.intent() == TradeIntentType.INQUIRE_STOCK)
                && (itemId == null || itemId.isBlank())) {
            itemId = session.lastRequestedItemId == null ? "" : session.lastRequestedItemId;
        }
        int quantity = classified.quantity() <= 0 ? 1 : classified.quantity();
        Integer counter = classified.counterTotalPrice();
        if (counter != null && counter <= 0) {
            counter = null;
        }
        return new ParsedTradeIntent(classified.intent(), itemId == null ? "" : itemId, quantity, counter);
    }

    private ParsedTradeIntent resolveTradeIntent(ParsedTradeIntent llmIntent, TradeSession session) {
        if (llmIntent.type() == TradeIntentType.NONE) {
            long age = System.currentTimeMillis() - session.lastInteractionAtMillis;
            if (age <= 60_000L && session.lastRequestedItemId != null && !session.lastRequestedItemId.isBlank()) {
                return new ParsedTradeIntent(TradeIntentType.INQUIRE_STOCK, session.lastRequestedItemId, 1, null);
            }
            if (age <= 60_000L && session.activeOffer != null) {
                return new ParsedTradeIntent(TradeIntentType.INQUIRE_SESSION_STATUS, "", 0, null);
            }
            return ParsedTradeIntent.none();
        }
        String itemId = llmIntent.itemId();
        if ((itemId == null || itemId.isBlank())
                && (llmIntent.type() == TradeIntentType.REQUEST_OFFER
                        || llmIntent.type() == TradeIntentType.INQUIRE_STOCK)
                && session.lastRequestedItemId != null
                && !session.lastRequestedItemId.isBlank()) {
            itemId = session.lastRequestedItemId;
        }

        int quantity = llmIntent.quantity();
        if (quantity <= 0) {
            quantity = 1;
        }

        Integer counter = llmIntent.counterTotalPrice();
        if (llmIntent.type() != TradeIntentType.COUNTER_OFFER) {
            counter = null;
        }
        if (counter != null && counter <= 0) {
            counter = null;
        }

        return new ParsedTradeIntent(
                llmIntent.type(),
                itemId == null ? "" : itemId,
                quantity,
                counter);
    }

    private void notifyNearbyPlayers(MinecraftServer server, Villager villager, String text) {
        Component message = Component.literal(text);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level() == villager.level() && player.distanceToSqr(villager) <= 160_000.0) {
                player.sendSystemMessage(message);
            }
        }
    }

    private double squaredDistance(BlockPos a, BlockPos b) {
        int dx = a.getX() - b.getX();
        int dy = a.getY() - b.getY();
        int dz = a.getZ() - b.getZ();
        return (double) dx * dx + (double) dy * dy + (double) dz * dz;
    }

    private boolean hasLongTermMemory(MemoryContext memory) {
        return memory != null
                && memory.longTerm() != null
                && !memory.longTerm().isEmpty();
    }

    private String generateRelationshipGreeting(
            UUID npcId,
            Villager villager,
            ServerPlayer owner,
            MemoryContext memory) {
        String fallback = "Welcome back. Last time we spoke, you were working on one of your goals."
                + " Want to continue from there or trade?";
        String prompt = promptFactory.relationshipGreetingPrompt(
                villager.getName().getString(),
                owner.getName().getString(),
                memory);
        tradeLlmInFlightByNpc.put(npcId, true);
        try {
            String responseText = parseRecipeResponseText(llmRouter.generate(prompt));
            return groundedRelationshipGreetingText(responseText, fallback);
        } finally {
            tradeLlmInFlightByNpc.put(npcId, false);
        }
    }

    private String groundedRelationshipGreetingText(String draftText, String fallback) {
        if (draftText == null || draftText.isBlank()) {
            return fallback;
        }

        return draftText;
    }

    private record TradeSessionKey(UUID npcId, UUID playerId) {
    }

    private static final class TradeSession {
        private TradeOffer activeOffer;
        private long lastInteractionAtMillis = 0L;
        private String lastRequestedItemId = "";
        private int lastRequestedQuantity = 1;

        private TradeSession() {
        }
    }

    private record RecipeSummary(String summaryText, List<RecipeIngredientNeed> ingredients) {
    }

    private record RecipeIngredientNeed(
            Item item,
            String itemId,
            String readableName,
            int count) {
    }

    private record RecipeIngredientStatus(
            Item item,
            String itemId,
            String readableName,
            int neededCount,
            int nearbyCount,
            boolean playerHasEnough) {
    }

    private record SearchRequest(
            String label,
            String entityTypeId,
            boolean requirePinkSheep) {
    }

    private enum SearchPhase {
        FIND_TARGET,
        MOVE_TO_TARGET,
        RETURN_TO_PLAYER,
        BEACON,
        DONE
    }

    private enum MineToChestPhase {
        FIND_OR_MOVE_TO_BLOCK,
        MINE_TARGET_BLOCK,
        MOVE_TO_CHEST,
        DEPOSIT_TO_CHEST,
        DONE
    }

    private enum MineToPlayerPhase {
        FIND_OR_MOVE_TO_BLOCK,
        MINE_TARGET_BLOCK,
        MOVE_TO_PLAYER,
        GIVE_TO_PLAYER,
        DONE
    }

    private enum DeliveryPhase {
        MOVE_TO_CHEST,
        WITHDRAW_FROM_CHEST,
        MOVE_TO_PLAYER,
        DROP_TO_PLAYER,
        DONE
    }

    private static final class DeliveryTask {
        private final Item item;
        private final String itemId;
        private final int count;
        private final BlockPos chestPos;
        private final UUID targetPlayerId;
        private DeliveryPhase phase = DeliveryPhase.MOVE_TO_CHEST;
        private ItemStack heldStack = ItemStack.EMPTY;

        private DeliveryTask(Item item, String itemId, int count, BlockPos chestPos, UUID targetPlayerId) {
            this.item = item;
            this.itemId = itemId;
            this.count = count;
            this.chestPos = chestPos;
            this.targetPlayerId = targetPlayerId;
        }
    }

    private static final class SearchTask {
        private final String targetLabel;
        private final String entityTypeId;
        private final boolean requirePinkSheep;
        private final UUID requesterId;
        private int searchRadius = SEARCH_BASE_RADIUS;
        private int scanTickCounter = 0;
        private int failedScans = 0;
        private int lastAnnouncedRadius = SEARCH_BASE_RADIUS;
        private long nextStatusAtMillis = 0L;
        private int lastPatrolAssignTick = 0;
        private int patrolNoProgressTicks = 0;
        private double lastPatrolDistance = Double.MAX_VALUE;
        private UUID targetEntityId;
        private BlockPos lastKnownTargetPos;
        private BlockPos patrolTarget;
        private SearchPhase phase = SearchPhase.FIND_TARGET;
        private int beaconTicksRemaining = 0;

        private SearchTask(String targetLabel, String entityTypeId, boolean requirePinkSheep, UUID requesterId) {
            this.targetLabel = targetLabel;
            this.entityTypeId = entityTypeId;
            this.requirePinkSheep = requirePinkSheep;
            this.requesterId = requesterId;
        }
    }

    private enum ScoutPhase {
        TRAVEL_OUTBOUND,
        SURVEY_AREA,
        RETURN_TO_PLAYER,
        REPORT,
        DONE
    }

    private static final class ScoutTask {
        private final UUID requesterId;
        private final String requesterName;
        private final String objective;
        private final String direction;
        private final int distance;
        private final String focus;
        private final BlockPos waypoint;
        private ScoutPhase phase = ScoutPhase.TRAVEL_OUTBOUND;
        private WorldSnapshot surveySnapshot;
        private int surveyTicksRemaining = SCOUT_SURVEY_TICKS;
        private long nextStatusAtMillis = 0L;
        private long outboundDeadlineMillis = 0L;
        private long returnDeadlineMillis = 0L;

        private ScoutTask(
                UUID requesterId,
                String requesterName,
                String objective,
                String direction,
                int distance,
                String focus,
                BlockPos waypoint) {
            this.requesterId = requesterId;
            this.requesterName = requesterName;
            this.objective = objective;
            this.direction = direction;
            this.distance = distance;
            this.focus = focus;
            this.waypoint = waypoint;
        }
    }

    private static final class MineToChestTask {
        private final Block block;
        private final Item item;
        private final String blockId;
        private final int count;
        private final BlockPos chestPos;
        private int minedCount = 0;
        private BlockPos targetPos;
        private ItemStack heldStack = ItemStack.EMPTY;
        private MineToChestPhase phase = MineToChestPhase.FIND_OR_MOVE_TO_BLOCK;

        private MineToChestTask(Block block, Item item, String blockId, int count, BlockPos chestPos) {
            this.block = block;
            this.item = item;
            this.blockId = blockId;
            this.count = count;
            this.chestPos = chestPos;
        }
    }

    private static final class MineToPlayerTask {
        private final Block block;
        private final Item item;
        private final String blockId;
        private final int count;
        private final UUID targetPlayerId;
        private int minedCount = 0;
        private BlockPos targetPos;
        private ItemStack heldStack = ItemStack.EMPTY;
        private final List<BlockPos> blockedTargets = new ArrayList<>();
        private int noPathAttempts = 0;
        private int searchRadius = MINE_SCAN_RADIUS;
        private MineToPlayerPhase phase = MineToPlayerPhase.FIND_OR_MOVE_TO_BLOCK;

        private MineToPlayerTask(Block block, Item item, String blockId, int count, UUID targetPlayerId) {
            this.block = block;
            this.item = item;
            this.blockId = blockId;
            this.count = count;
            this.targetPlayerId = targetPlayerId;
        }
    }

    // ── Dialogue Reply ───────────────────────────────────────────────────────

    public boolean tryHandleDialogueInstruction(
            MinecraftServer server,
            UUID npcId,
            String fallbackName,
            String playerName,
            UUID ownerPlayerId,
            String instruction,
            MemoryContext memory,
            WorldSnapshot snapshot) {
        if (instruction == null || instruction.isBlank()) return false;
        Villager villager = findVillager(server, npcId);
        if (villager == null) { logger.info("[DIALOGUE] skip: villager not found npc={}", npcId); return false; }
        ServerPlayer player = findPlayerByName(server, playerName);
        if (player == null) { logger.info("[DIALOGUE] skip: player '{}' not found", playerName); return false; }
        if (player.level() != villager.level()) { logger.info("[DIALOGUE] skip: player/villager in different levels"); return false; }
        String scenarioContext = buildScenarioContextForDialogue(server, villager, npcId);
        String replyPrompt = promptFactory.dialogueReplyPrompt(
                villager.getName().getString(), player.getName().getString(), instruction, snapshot, memory,
                scenarioContext);
        String replyRaw = llmRouter.generate(replyPrompt);
        String text = parseDialogueText(replyRaw);
        if (text == null || text.isBlank()) {
            text = "Hmm, I'm not sure how to respond to that.";
        }

        speakAsNpc(server, villager, fallbackName, text);
        logger.info("[DIALOGUE] npc={} player={} reply='{}'", fallbackName, playerName, text);
        return true;
    }

    public enum NpcState { BUILDING, SCOUTING, SEARCHING, IDLE }

    public NpcState getNpcState(UUID npcId) {
        if (activeScenarioTasks.containsKey(npcId)) return NpcState.BUILDING;
        if (activeScoutTasks.containsKey(npcId))    return NpcState.SCOUTING;
        if (activeSearchTasks.containsKey(npcId))   return NpcState.SEARCHING;
        return NpcState.IDLE;
    }

    private String buildScenarioContextForDialogue(MinecraftServer server, Villager villager, UUID npcId) {
        ScenarioTask task = activeScenarioTasks.get(npcId);
        if (task == null) return null;
        ServerLevel level = villager.level() instanceof ServerLevel sl ? sl : null;
        StringBuilder sb = new StringBuilder("Active build task: ").append(task.scenarioName).append(". Materials needed:");
        for (com.example.ai.scenario.ScenarioStep step : task.steps) {
            if ("fetch_from_chest".equals(step.intent())) {
                com.google.gson.JsonObject p = step.parameters();
                String itemId = p.has("item_id") ? p.get("item_id").getAsString() : "unknown";
                int count = p.has("count") ? p.get("count").getAsInt() : 0;
                sb.append(" ").append(count).append("x ").append(itemId);
                if (level != null) {
                    Item item = resolveKnownItem(itemId);
                    if (item != null) {
                        int inChest = countItemInNearbyChests(server, level, villager.blockPosition(), item, CHEST_SCAN_RADIUS);
                        sb.append(" (").append(inChest).append(" currently in nearby chest)");
                    }
                }
                sb.append(";");
            }
        }
        return sb.toString();
    }

    private String parseDialogueText(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}');
            if (start >= 0 && end > start) {
                JsonObject root = JsonParser.parseString(raw.substring(start, end + 1)).getAsJsonObject();
                for (String key : new String[]{"text", "reply", "response", "message", "response_text"}) {
                    if (root.has(key) && root.get(key).isJsonPrimitive()) {
                        String val = root.get(key).getAsString().trim();
                        if (!val.isBlank() && val.length() <= 300) return val;
                    }
                }
            }
        } catch (Exception ignored) {}
        // Fallback: use raw text when model returns plain text instead of JSON.
        // Reject if it still looks like a JSON fragment (LLM returned an action blob instead of dialogue).
        String trimmed = raw.trim();
        if (trimmed.contains("\"intent\"") || trimmed.contains("\"parameters\"") || trimmed.contains("\"reasoning\"")) {
            return null;
        }
        int colonIdx = trimmed.indexOf(':');
        if (colonIdx > 0 && colonIdx < 20) {
            trimmed = trimmed.substring(colonIdx + 1).trim();
        }
        return (trimmed.isBlank() || trimmed.length() > 300) ? null : trimmed;
    }

    // ── Scenario Builder ─────────────────────────────────────────────────────

    private boolean startScenarioDirect(
            MinecraftServer server,
            UUID npcId,
            Villager villager,
            String fallbackName,
            JsonObject parameters,
            String reasoning) {
        if (activeScenarioTasks.containsKey(npcId)) {
            speakAsNpc(server, villager, fallbackName, "I am already working on a task.");
            return true;
        }
        ServerPlayer nearest = nearestPlayer(server, villager);
        if (nearest == null) return false;

        String desc = parameters.has("description") && !parameters.get("description").getAsString().isBlank()
                ? parameters.get("description").getAsString()
                : reasoning;
        if (desc == null || desc.isBlank()) return false;

        WorldSnapshot snap = captureWorldSnapshot(server, npcId, fallbackName);
        String planPrompt = promptFactory.scenarioPlanningPrompt(
                villager.getName().getString(), nearest.getName().getString(), desc, snap);
        ScenarioPlan plan = scenarioPlanParser.parse(llmRouter.generate(planPrompt));
        if (!plan.isValid()) {
            speakAsNpc(server, villager, fallbackName, "I could not plan that task. Could you be more specific?");
            return true;
        }

        ScenarioTask task = new ScenarioTask(
                nearest.getUUID(), nearest.getName().getString(), plan.scenario(), plan.steps());
        activeScenarioTasks.put(npcId, task);
        String announce = plan.announce() == null || plan.announce().isBlank()
                ? "Starting " + plan.scenario() + "."
                : plan.announce();
        speakAsNpc(server, villager, fallbackName, announce);
        logger.info("[SCENARIO] npc={} started via build_structure desc='{}' steps={}",
                fallbackName, desc, plan.steps().size());
        return true;
    }

    public boolean tryHandleScenarioInstruction(
            MinecraftServer server,
            UUID npcId,
            String fallbackName,
            String playerName,
            UUID ownerPlayerId,
            String instruction,
            MemoryContext memory,
            WorldSnapshot snapshot,
            boolean forceStart) {
        if (instruction == null || instruction.isBlank()) return false;
        Villager villager = findVillager(server, npcId);
        if (villager == null || !(villager.level() instanceof ServerLevel)) return false;
        ServerPlayer player = findPlayerByName(server, playerName);
        if (player == null || player.level() != villager.level()) return false;
        if (ownerPlayerId != null && !ownerPlayerId.equals(player.getUUID())) return false;
        if (activeScenarioTasks.containsKey(npcId)) {
            speakAsNpc(server, villager, fallbackName, "I am already working on a task.");
            return true;
        }

        if (!forceStart) {
            String classifyPrompt = promptFactory.scenarioIntentPrompt(
                    villager.getName().getString(), player.getName().getString(), instruction);
            String classifyRaw = llmRouter.generate(classifyPrompt);
            if (!isScenarioIntent(classifyRaw)) return false;
        }

        String planPrompt = promptFactory.scenarioPlanningPrompt(
                villager.getName().getString(), player.getName().getString(), instruction, snapshot);
        String planRaw = llmRouter.generate(planPrompt);
        ScenarioPlan plan = scenarioPlanParser.parse(planRaw);
        if (!plan.isValid()) {
            speakAsNpc(server, villager, fallbackName, "I could not plan that. Could you be more specific?");
            return true;
        }

        ScenarioTask task = new ScenarioTask(
                player.getUUID(), player.getName().getString(), plan.scenario(), plan.steps());
        activeScenarioTasks.put(npcId, task);
        lastActionSummaryByNpc.put(npcId, buildScenarioPlanSummary(plan));
        String announce = plan.announce() == null || plan.announce().isBlank()
                ? "Starting " + plan.scenario() + "."
                : plan.announce();
        speakAsNpc(server, villager, fallbackName, announce);
        logger.info("[SCENARIO] npc={} player={} scenario='{}' steps={}",
                fallbackName, playerName, plan.scenario(), plan.steps().size());
        return true;
    }

    public String lastActionSummary(UUID npcId) {
        return lastActionSummaryByNpc.getOrDefault(npcId, "");
    }

    private String buildScenarioPlanSummary(com.example.ai.scenario.ScenarioPlan plan) {
        StringBuilder sb = new StringBuilder("Planned to build ").append(plan.scenario()).append(".");
        for (com.example.ai.scenario.ScenarioStep step : plan.steps()) {
            if ("fetch_from_chest".equals(step.intent())) {
                JsonObject p = step.parameters();
                String itemId = p.has("item_id") ? p.get("item_id").getAsString() : "unknown";
                int count = p.has("count") ? p.get("count").getAsInt() : 0;
                sb.append(" Requires ").append(count).append("x ").append(itemId).append(" from a chest.");
            }
        }
        return sb.toString();
    }

    // ── Direct execution methods (called after action selection already classified the intent) ─

    public boolean tryHandleRecipeInstructionDirect(
            MinecraftServer server, UUID npcId, String fallbackName,
            String playerName, UUID ownerPlayerId,
            String instruction, MemoryContext memory, WorldSnapshot snapshot) {
        if (instruction == null || instruction.isBlank()) return false;
        String lower = instruction.toLowerCase(Locale.ROOT);
        Villager villager = findVillager(server, npcId);
        if (villager == null) return false;
        ServerPlayer player = findPlayerByName(server, playerName);
        if (player == null || player.level() != villager.level()) return false;
        if (ownerPlayerId != null && !ownerPlayerId.equals(player.getUUID())) return false;

        Item targetOutput = recipeTargetItem(lower, instruction);
        if (targetOutput == null) return false;

        RecipeSummary recipeSummary = summarizeRecipeFromGame(server, targetOutput);
        long now = System.currentTimeMillis();
        TradeSessionKey key = new TradeSessionKey(npcId, player.getUUID());
        TradeSession session = tradeSessions.computeIfAbsent(key, ignored -> new TradeSession());
        session.lastInteractionAtMillis = now;
        ServerLevel level = (ServerLevel) villager.level();
        List<RecipeIngredientStatus> ingredientStatuses = evaluateIngredientStatus(
                server, level, villager.blockPosition(), player, recipeSummary.ingredients());

        List<String> missingIngredients = new ArrayList<>();
        List<String> availableIngredients = new ArrayList<>();
        for (RecipeIngredientStatus status : ingredientStatuses) {
            String entry = status.readableName() + " " + status.nearbyCount() + "/" + status.neededCount();
            if (status.nearbyCount() >= status.neededCount()) availableIngredients.add(entry);
            else missingIngredients.add(entry);
        }
        String missingSummary = missingIngredients.isEmpty() ? "none" : String.join(", ", missingIngredients);
        String availableSummary = availableIngredients.isEmpty() ? "none" : String.join(", ", availableIngredients);

        StringBuilder fallbackReply = new StringBuilder();
        fallbackReply.append("You need ").append(recipeSummary.summaryText()).append(".");
        if (!ingredientStatuses.isEmpty()) {
            fallbackReply.append(" Nearby stock: ");
            List<String> stockBits = new ArrayList<>();
            for (RecipeIngredientStatus status : ingredientStatuses) {
                stockBits.add(status.readableName() + " " + status.nearbyCount() + "/" + status.neededCount());
            }
            fallbackReply.append(String.join(", ", stockBits)).append(".");
        }

        TradeOffer ingredientOffer = null;
        RecipeIngredientStatus offeredStatus = null;
        for (RecipeIngredientStatus status : ingredientStatuses) {
            if (!tradeOfferEngine.supportsItem(status.itemId()) || status.nearbyCount() <= 0) continue;
            int offerCount = Math.min(status.neededCount(), Math.max(1, status.nearbyCount()));
            ingredientOffer = tradeOfferEngine.quote(status.itemId(), offerCount, status.nearbyCount(), 0, false, now);
            offeredStatus = status;
            break;
        }
        if (ingredientOffer != null && offeredStatus != null) {
            session.activeOffer = ingredientOffer;
            session.lastInteractionAtMillis = now;
            session.lastRequestedItemId = ingredientOffer.itemId();
            session.lastRequestedQuantity = ingredientOffer.quantity();
            fallbackReply.append(" I can trade ").append(ingredientOffer.quantity()).append(" ")
                    .append(offeredStatus.readableName()).append(" for ").append(ingredientOffer.totalPrice())
                    .append(" emeralds if you want.");
        }
        fallbackReply.append(" I can help gather what is missing.");

        String nearbyFacts = summarizeIngredientFacts(ingredientStatuses);
        String requiredFacts = "recipe=" + recipeSummary.summaryText()
                + "; output_item=" + readableItemName(BuiltInRegistries.ITEM.getKey(targetOutput).toString())
                + "; nearby_ingredients=" + nearbyFacts
                + "; missing_ingredients=" + missingSummary
                + "; available_ingredients=" + availableSummary
                + "; active_offer=" + (ingredientOffer == null ? "none" : summarizeOffer(ingredientOffer));
        String prompt = promptFactory.recipeAssistantPrompt(
                villager.getName().getString(), player.getName().getString(),
                instruction, requiredFacts, snapshot, memory);
        String responseText = parseRecipeResponseText(llmRouter.generate(prompt));
        speakAsNpc(server, villager, fallbackName,
                groundedRecipeText(responseText, fallbackReply.toString(), ingredientOffer,
                        recipeSummary.summaryText(), nearbyFacts));
        lastActionSummaryByNpc.put(npcId, "Explained recipe for "
                + readableItemName(BuiltInRegistries.ITEM.getKey(targetOutput).toString())
                + ": " + recipeSummary.summaryText()
                + (missingIngredients.isEmpty() ? ". All ingredients available." : ". Missing: " + missingSummary + "."));
        return true;
    }

    public boolean executeSearchDirect(
            MinecraftServer server, UUID npcId, String fallbackName,
            String playerName, UUID ownerPlayerId,
            String entityId, String entityLabel) {
        if (entityId == null || entityId.isBlank()) return false;
        Villager villager = findVillager(server, npcId);
        if (villager == null || !(villager.level() instanceof ServerLevel)) return false;
        ServerPlayer player = findPlayerByName(server, playerName);
        if (player == null || player.level() != villager.level()) return false;
        if (ownerPlayerId != null && !ownerPlayerId.equals(player.getUUID())) return false;
        if (activeSearchTasks.containsKey(npcId)) {
            String label = entityLabel != null && !entityLabel.isBlank() ? entityLabel : entityId;
            speakSearchStatusWithLlm(server, villager, fallbackName, player.getName().getString(),
                    "search_already_active", "target=" + label, "I am already running a search task.");
            return true;
        }
        String label = entityLabel != null && !entityLabel.isBlank() ? entityLabel : entityId;
        SearchTask task = new SearchTask(label, entityId, false, player.getUUID());
        task.nextStatusAtMillis = System.currentTimeMillis() + SEARCH_STATUS_COOLDOWN_MILLIS;
        activeSearchTasks.put(npcId, task);
        lastActionSummaryByNpc.put(npcId, "Searching for " + label + " (entity: " + entityId + "). Will report location when found.");
        suppressTradeGreeting(npcId, player.getUUID(), SEARCH_TRADE_SUPPRESS_MILLIS);
        speakSearchStatusWithLlm(server, villager, fallbackName, player.getName().getString(),
                "search_start", "target=" + label,
                "Got it. I will search for " + label + " and call out its location when I find it.");
        return true;
    }

    public boolean executeScoutDirect(
            MinecraftServer server, UUID npcId, String fallbackName,
            String playerName, UUID ownerPlayerId,
            String instruction, JsonObject parameters) {
        Villager villager = findVillager(server, npcId);
        if (villager == null || !(villager.level() instanceof ServerLevel)) return false;
        ServerPlayer player = findPlayerByName(server, playerName);
        if (player == null || player.level() != villager.level()) return false;
        if (ownerPlayerId != null && !ownerPlayerId.equals(player.getUUID())) return false;
        if (activeScoutTasks.containsKey(npcId)) {
            speakAsNpc(server, villager, fallbackName, "I am already scouting.");
            return true;
        }
        if (!startScoutTask(server, npcId, villager, player.getUUID(), player.getName().getString(),
                instruction, parameters)) {
            return false;
        }
        String direction = parameters != null && parameters.has("direction")
                ? parameters.get("direction").getAsString() : "around";
        String distance = parameters != null && parameters.has("distance")
                ? String.valueOf(Math.max(24, parameters.get("distance").getAsInt()))
                : String.valueOf(SCOUT_BASE_DISTANCE);
        lastActionSummaryByNpc.put(npcId, "Scouting " + direction + " for approximately "
                + distance + " blocks to observe the area.");
        speakScoutStatus(server, villager, fallbackName, player.getName().getString(), "scout_start",
                "direction=" + direction + "; distance=" + distance + "; objective=" + instruction,
                "I will scout the area and report back.");
        return true;
    }

    private boolean isScenarioIntent(String raw) {
        if (raw == null || raw.isBlank()) return false;
        try {
            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}');
            if (start < 0 || end <= start) return false;
            JsonObject root = JsonParser.parseString(raw.substring(start, end + 1)).getAsJsonObject();
            String intent = root.has("intent") ? root.get("intent").getAsString() : "";
            return "scenario_builder".equalsIgnoreCase(intent);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean advanceScenarioTask(MinecraftServer server, Villager villager, String fallbackName, ScenarioTask task) {
        if (!(villager.level() instanceof ServerLevel level)) return false;
        if (task.cancelled) return false;
        if (task.waitingForMaterials) {
            if (System.currentTimeMillis() < task.retryFetchAt) return true;
            task.waitingForMaterials = false;
            task.stepStarted = false;
        }
        UUID npcId = villager.getUUID();

        // Sub-task completion: advance the step index when delegated work finishes
        if (task.waitingForDelivery) {
            if (activeDeliveries.containsKey(npcId)) return true;
            task.waitingForDelivery = false;
            task.stepStarted = false;
            task.currentStepIndex++;
        }
        if (task.waitingForMineToPlayer) {
            if (activeMineToPlayer.containsKey(npcId)) return true;
            task.waitingForMineToPlayer = false;
            task.stepStarted = false;
            task.currentStepIndex++;
        }
        if (task.waitingForMineToChest) {
            if (activeMineToChest.containsKey(npcId)) return true;
            task.waitingForMineToChest = false;
            task.stepStarted = false;
            task.currentStepIndex++;
        }

        // move_to tracking: stay in this step until arrived or timeout
        if (task.isMoveStep) {
            if (villager.getNavigation().isDone()) {
                villager.getNavigation().moveTo(task.moveTargetX, task.moveTargetY, task.moveTargetZ, 0.9);
            }
            boolean arrived = villager.distanceToSqr(task.moveTargetX, task.moveTargetY, task.moveTargetZ) <= 9.0;
            long nowMs = System.currentTimeMillis();
            boolean timedOut = task.stepDeadlineMillis > 0 && nowMs >= task.stepDeadlineMillis;
            if (!arrived && !timedOut) return true;
            task.isMoveStep = false;
            task.stepStarted = false;
            task.stepDeadlineMillis = 0L;
            task.currentStepIndex++;
        }

        if (task.currentStepIndex >= task.steps.size()) {
            ServerPlayer requester = server.getPlayerList().getPlayer(task.requesterId);
            if (requester != null) {
                speakAsNpc(server, villager, fallbackName,
                        "I have finished the " + task.scenarioName + " task.");
            }
            recordTaskSummary(villager.getUUID(),
                    "Completed task '" + task.scenarioName + "' for "
                    + (requester != null ? requester.getName().getString() : "player") + ".");
            return false;
        }

        // Only execute the step if it hasn't been started yet
        if (task.stepStarted) return true;

        ScenarioStep step = task.steps.get(task.currentStepIndex);
        executeScenarioStep(server, npcId, villager, fallbackName, task, step);

        // Delegated steps (move_to, fetch, mine) set a flag above; mark started so we don't re-execute
        // Immediate steps (place_block, place_pattern, dialogue_reply) already incremented currentStepIndex
        if (task.isMoveStep || task.waitingForDelivery || task.waitingForMineToPlayer || task.waitingForMineToChest || task.waitingForMaterials) {
            task.stepStarted = true;
            task.stepDeadlineMillis = System.currentTimeMillis() + 30_000L;
        }

        return task.currentStepIndex < task.steps.size()
                || task.isMoveStep || task.waitingForDelivery || task.waitingForMineToPlayer || task.waitingForMineToChest;
    }

    private void executeScenarioStep(
            MinecraftServer server,
            UUID npcId,
            Villager villager,
            String fallbackName,
            ScenarioTask task,
            ScenarioStep step) {
        if (!(villager.level() instanceof ServerLevel level)) return;
        logger.info("[SCENARIO] npc={} executing step {} intent={} desc={}",
                fallbackName, task.currentStepIndex, step.intent(), step.description());

        switch (step.intent()) {
            case "move_to" -> {
                JsonObject p = step.parameters();
                if (p.has("direction")) {
                    String dir = p.get("direction").getAsString().toLowerCase(Locale.ROOT);
                    int dist = p.has("distance") ? p.get("distance").getAsInt() : 12;
                    ServerPlayer requester = server.getPlayerList().getPlayer(task.requesterId);
                    BlockPos anchor = requester != null ? requester.blockPosition() : villager.blockPosition();
                    Vec3 vec = switch (dir) {
                        case "north" -> new Vec3(0, 0, -1);
                        case "south" -> new Vec3(0, 0, 1);
                        case "east"  -> new Vec3(1, 0, 0);
                        case "west"  -> new Vec3(-1, 0, 0);
                        default      -> new Vec3(0, 0, 0);
                    };
                    task.moveTargetX = anchor.getX() + vec.x * dist;
                    task.moveTargetY = anchor.getY();
                    task.moveTargetZ = anchor.getZ() + vec.z * dist;
                } else {
                    task.moveTargetX = p.has("x") ? p.get("x").getAsDouble() : villager.getX();
                    task.moveTargetY = p.has("y") ? p.get("y").getAsDouble() : villager.getY();
                    task.moveTargetZ = p.has("z") ? p.get("z").getAsDouble() : villager.getZ();
                }
                task.isMoveStep = true;
                villager.getNavigation().moveTo(task.moveTargetX, task.moveTargetY, task.moveTargetZ, 0.9);
            }
            case "place_block" -> {
                JsonObject p = step.parameters();
                String blockId = p.has("block") ? p.get("block").getAsString() : "minecraft:cobblestone";
                int rx = p.has("relative_x") ? p.get("relative_x").getAsInt() : 0;
                int ry = p.has("relative_y") ? p.get("relative_y").getAsInt() : 0;
                int rz = p.has("relative_z") ? p.get("relative_z").getAsInt() : 0;
                executePlaceBlock(level, villager, blockId, rx, ry, rz);
                task.currentStepIndex++;
            }
            case "place_pattern" -> {
                JsonObject p = step.parameters();
                String blockId = p.has("block") ? p.get("block").getAsString() : "minecraft:cobblestone";
                String pattern = p.has("pattern") ? p.get("pattern").getAsString() : "floor";
                int width = p.has("width") ? Math.min(16, p.get("width").getAsInt()) : 3;
                int height = p.has("height") ? Math.min(16, p.get("height").getAsInt()) : 1;
                int length = p.has("length") ? Math.min(16, p.get("length").getAsInt()) : 3;
                int rx = p.has("relative_x") ? p.get("relative_x").getAsInt() : 0;
                int ry = p.has("relative_y") ? p.get("relative_y").getAsInt() : 0;
                int rz = p.has("relative_z") ? p.get("relative_z").getAsInt() : 0;
                executePlacePattern(level, villager, blockId, pattern, width, height, length, rx, ry, rz);
                task.currentStepIndex++;
            }
            case "fetch_from_chest" -> {
                JsonObject p = step.parameters();
                if (p.has("item_id")) {
                    if (startFetchTask(server, npcId, villager, p, !task.materialsMissingNotified)) {
                        task.waitingForDelivery = true;
                        task.waitingForMaterials = false;
                        task.materialsMissingNotified = false;
                    } else {
                        task.materialsMissingNotified = true;
                        task.waitingForMaterials = true;
                        task.retryFetchAt = System.currentTimeMillis() + 15_000L;
                    }
                } else {
                    task.currentStepIndex++;
                }
            }
            case "mine_to_player" -> {
                JsonObject p = step.parameters();
                if (p.has("block")) {
                    if (startMineToPlayerTask(server, npcId, villager, p)) {
                        task.waitingForMineToPlayer = true;
                    } else {
                        task.cancelled = true;
                    }
                } else {
                    task.currentStepIndex++;
                }
            }
            case "mine_block" -> {
                JsonObject p = step.parameters();
                if (p.has("block")) {
                    startMineToChestTask(server, npcId, villager, p);
                    task.waitingForMineToChest = true;
                } else {
                    task.currentStepIndex++;
                }
            }
            case "dialogue_reply" -> {
                JsonObject p = step.parameters();
                String text = p.has("text") ? p.get("text").getAsString() : step.description();
                speakAsNpc(server, villager, fallbackName, text);
                task.currentStepIndex++;
            }
            default -> {
                logger.warn("[SCENARIO] npc={} unknown step intent={}, skipping", fallbackName, step.intent());
                task.currentStepIndex++;
            }
        }
    }

    private void executePlaceBlock(ServerLevel level, Villager villager, String blockId, int rx, int ry, int rz) {
        Block block = resolvePlaceableBlock(blockId);
        if (block == null) {
            logger.warn("[SCENARIO] Unknown block id for placement: {}", blockId);
            return;
        }
        BlockPos npcPos = villager.blockPosition();
        BlockPos target = npcPos.offset(rx, ry, rz);
        level.setBlockAndUpdate(target, block.defaultBlockState());
        logger.info("[SCENARIO] Placed {} at {}", blockId, target);
    }

    private void executePlacePattern(
            ServerLevel level, Villager villager,
            String blockId, String pattern,
            int width, int height, int length,
            int rx, int ry, int rz) {
        Block block = resolvePlaceableBlock(blockId);
        if (block == null) {
            logger.warn("[SCENARIO] Unknown block id for pattern: {}", blockId);
            return;
        }
        BlockPos origin = villager.blockPosition().offset(rx, ry, rz);
        String patternKey = pattern.toLowerCase(Locale.ROOT);
        List<BlockPos> positions = switch (patternKey) {
            case "wall"    -> wallPositions(origin, width, height);
            case "pillar"  -> pillarPositions(origin, height);
            case "outline" -> outlinePositions(origin, width, length);
            case "hut"     -> hutPositions(origin, width, height, length);
            default        -> floorPositions(origin, width, length);
        };
        // Floors, outlines and huts overwrite existing ground; walls/pillars only fill air
        boolean overwrite = patternKey.equals("floor") || patternKey.equals("outline") || patternKey.equals("hut");
        int placed = 0;
        for (BlockPos pos : positions) {
            if (overwrite || level.getBlockState(pos).isAir()) {
                level.setBlockAndUpdate(pos, block.defaultBlockState());
                placed++;
            }
        }
        logger.info("[SCENARIO] Placed {} blocks of {} in {} pattern at {}", placed, blockId, pattern, origin);
    }

    private List<BlockPos> floorPositions(BlockPos origin, int width, int length) {
        List<BlockPos> list = new ArrayList<>();
        for (int dx = 0; dx < width; dx++) {
            for (int dz = 0; dz < length; dz++) {
                list.add(origin.offset(dx, 0, dz));
            }
        }
        return list;
    }

    private List<BlockPos> wallPositions(BlockPos origin, int width, int height) {
        List<BlockPos> list = new ArrayList<>();
        for (int dx = 0; dx < width; dx++) {
            for (int dy = 0; dy < height; dy++) {
                list.add(origin.offset(dx, dy, 0));
            }
        }
        return list;
    }

    private List<BlockPos> hutPositions(BlockPos origin, int width, int height, int length) {
        java.util.LinkedHashSet<BlockPos> set = new java.util.LinkedHashSet<>();

        for (int dx = 0; dx < width; dx++)
            for (int dz = 0; dz < length; dz++)
                set.add(origin.offset(dx, 0, dz));

        for (int dy = 1; dy < height; dy++) {
            for (int dx = 0; dx < width; dx++) {
                set.add(origin.offset(dx, dy, 0));
                set.add(origin.offset(dx, dy, length - 1));
            }
            for (int dz = 1; dz < length - 1; dz++) {
                set.add(origin.offset(0, dy, dz));
                set.add(origin.offset(width - 1, dy, dz));
            }
        }

        for (int dx = 0; dx < width; dx++)
            for (int dz = 0; dz < length; dz++)
                set.add(origin.offset(dx, height, dz));
        return new ArrayList<>(set);
    }

    private List<BlockPos> pillarPositions(BlockPos origin, int height) {
        List<BlockPos> list = new ArrayList<>();
        for (int dy = 0; dy < height; dy++) {
            list.add(origin.offset(0, dy, 0));
        }
        return list;
    }

    private List<BlockPos> outlinePositions(BlockPos origin, int width, int length) {
        List<BlockPos> list = new ArrayList<>();
        for (int dx = 0; dx < width; dx++) {
            list.add(origin.offset(dx, 0, 0));
            list.add(origin.offset(dx, 0, length - 1));
        }
        for (int dz = 1; dz < length - 1; dz++) {
            list.add(origin.offset(0, 0, dz));
            list.add(origin.offset(width - 1, 0, dz));
        }
        return list;
    }

    private Block resolvePlaceableBlock(String blockId) {
        return resolveKnownBlock(blockId);
    }

    private enum ScenarioPhase {
        EXECUTING,
        DONE
    }

    private static final class ScenarioTask {
        private final UUID requesterId;
        private final String requesterName;
        private final String scenarioName;
        private final List<ScenarioStep> steps;
        private int currentStepIndex = 0;
        private boolean stepStarted = false;
        private long stepDeadlineMillis = 0L;
        private double moveTargetX;
        private double moveTargetY;
        private double moveTargetZ;
        private boolean isMoveStep = false;
        private boolean waitingForDelivery = false;
        private boolean waitingForMineToPlayer = false;
        private boolean waitingForMineToChest = false;
        private boolean cancelled = false;
        private boolean waitingForMaterials = false;
        private long retryFetchAt = 0L;
        private boolean materialsMissingNotified = false;

        private ScenarioTask(UUID requesterId, String requesterName, String scenarioName, List<ScenarioStep> steps) {
            this.requesterId = requesterId;
            this.requesterName = requesterName;
            this.scenarioName = scenarioName;
            this.steps = steps;
        }
    }
}
