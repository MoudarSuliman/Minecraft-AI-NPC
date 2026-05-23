package com.example.ai.action;

import com.example.ai.intent.AgentDecision;
import com.example.ai.llm.LlmRouter;
import com.example.ai.memory.MemoryContext;
import com.example.ai.perception.SemanticPerceptionService;
import com.example.ai.perception.WorldSnapshot;
import com.example.ai.prompt.PromptFactory;
import com.example.ai.trade.ParsedTradeIntent;
import com.example.ai.trade.TradeNegotiationDraft;
import com.example.ai.trade.TradeNegotiationParser;
import com.example.ai.trade.TradeCounterResult;
import com.example.ai.trade.TradeIntentClassifierDraft;
import com.example.ai.trade.TradeIntentClassifierParser;
import com.example.ai.trade.TradeIntentType;
import com.example.ai.trade.TradeOffer;
import com.example.ai.trade.TradeOfferEngine;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
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

public final class AgentActionExecutor {
    private static final int CHEST_SCAN_RADIUS = 16;
    private static final int MINE_SCAN_RADIUS = 8;
    private static final int TRADE_SCAN_RADIUS = 18;
    private static final double TRADE_INTENT_CONFIDENCE_THRESHOLD = 0.6;
    private static final boolean TRADE_DEBUG_CHAT = true;
    private static final long TRADE_GREETING_COOLDOWN_MILLIS = 20_000L;
    private static final long TRADE_RECENT_INTERACTION_SUPPRESS_MILLIS = 45_000L;
    private static final double OWNER_IDLE_RADIUS_SQR = 36.0;   // 6 blocks
    private static final double OWNER_PULL_RADIUS_SQR = 196.0;  // 14 blocks
    private static final double OWNER_TELEPORT_RADIUS_SQR = 3600.0; // 60 blocks
    private static final double TRADE_GREETING_RADIUS_SQR = 25.0; // 5 blocks

    private final Logger logger;
    private final PromptFactory promptFactory;
    private final LlmRouter llmRouter;
    private final SemanticPerceptionService perceptionService = new SemanticPerceptionService();
    private final TradeOfferEngine tradeOfferEngine = TradeOfferEngine.defaultEngine();
    private final TradeIntentClassifierParser tradeIntentClassifierParser = new TradeIntentClassifierParser();
    private final TradeNegotiationParser tradeNegotiationParser = new TradeNegotiationParser();
    private final Map<UUID, List<JsonObject>> actionOutbox = new ConcurrentHashMap<>();
    private final Map<UUID, DeliveryTask> activeDeliveries = new ConcurrentHashMap<>();
    private final Map<UUID, MineToChestTask> activeMineToChest = new ConcurrentHashMap<>();
    private final Map<UUID, MineToPlayerTask> activeMineToPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastTradeIntentByNpc = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> tradeLlmInFlightByNpc = new ConcurrentHashMap<>();
    private final Map<TradeSessionKey, TradeSession> tradeSessions = new ConcurrentHashMap<>();
    private final Map<TradeSessionKey, Long> nextTradeGreetingAt = new ConcurrentHashMap<>();

    public AgentActionExecutor(Logger logger, PromptFactory promptFactory, LlmRouter llmRouter) {
        this.logger = logger;
        this.promptFactory = promptFactory;
        this.llmRouter = llmRouter;
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
                environment
        );
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

    private WorldSnapshot.EnvironmentContext buildEnvironmentContext(ServerLevel level, BlockPos center, List<WorldSnapshot.SeenEntity> nearbyEntities) {
        String biome = level.getBiome(center)
                .unwrapKey()
                .map(Object::toString)
                .orElse(level.dimension().toString());
        long timeOfDay = level.getGameTime() % 24000L;
        boolean isNight = timeOfDay >= 13000L && timeOfDay <= 23000L;
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
            case "move_to" -> executeMove(server, villager, parameters);
            case "fetch_from_chest" -> startFetchTask(server, npcId, villager, parameters);
            case "mine_block" -> executeMineBlock(server, villager, parameters);
            case "mine_to_chest" -> startMineToChestTask(server, npcId, villager, parameters);
            case "mine_to_player" -> startMineToPlayerTask(server, npcId, villager, parameters);
            default -> true;
        };

        logger.info("Action execution: npc={} intent={} success={}", fallbackName, intent, success);
        return success;
    }

    public void enforceOwnerLeash(MinecraftServer server, UUID npcId, UUID ownerPlayerId, String fallbackName) {
        if (ownerPlayerId == null) {
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

    public void maybeSendTradeGreeting(MinecraftServer server, UUID npcId, String fallbackName, UUID ownerPlayerId) {
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

        nextTradeGreetingAt.put(key, now + TRADE_GREETING_COOLDOWN_MILLIS);
        session.lastInteractionAtMillis = now;
        speakAsNpc(server, villager, fallbackName, "You looking to trade?");
    }

    public boolean tryHandleRecipeInstruction(
            MinecraftServer server,
            UUID npcId,
            String fallbackName,
            String playerName,
            UUID ownerPlayerId,
            String instruction,
            MemoryContext memory,
            WorldSnapshot snapshot
    ) {
        if (instruction == null || instruction.isBlank()) {
            return false;
        }
        String lower = instruction.toLowerCase(Locale.ROOT);
        if (!isRecipeQuery(lower)) {
            return false;
        }

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

        Item targetOutput = recipeTargetItem(lower);
        if (targetOutput == null) {
            return false;
        }

        String recipeSummary = summarizeRecipeFromGame(server, targetOutput);
        Item coreMaterial = recipeCoreMaterial(targetOutput);
        int coreNeeded = requiredCoreCount(targetOutput);
        long now = System.currentTimeMillis();
        boolean playerHasRequiredSticks = hasAtLeast(player, Items.STICK, 2);
        int sticksNearby = countItemInNearbyChests((ServerLevel) villager.level(), villager.blockPosition(), Items.STICK, TRADE_SCAN_RADIUS);
        int coreNearby = coreMaterial == null
                ? 0
                : countItemInNearbyChests((ServerLevel) villager.level(), villager.blockPosition(), coreMaterial, TRADE_SCAN_RADIUS);

        StringBuilder fallbackReply = new StringBuilder();
        fallbackReply.append("You need ").append(recipeSummary).append(".");
        TradeOffer stickOffer = null;
        if (sticksNearby >= 2) {
            TradeSessionKey key = new TradeSessionKey(npcId, player.getUUID());
            TradeSession session = tradeSessions.computeIfAbsent(key, ignored -> new TradeSession());
            stickOffer = tradeOfferEngine.quote("minecraft:stick", 2, sticksNearby, 0, false, now);
            session.activeOffer = stickOffer;
            session.lastInteractionAtMillis = now;
            session.lastRequestedItemId = "minecraft:stick";
            session.lastRequestedQuantity = 2;
            fallbackReply.append(" I have ").append(sticksNearby).append(" sticks nearby and can trade 2 sticks for ")
                    .append(stickOffer.totalPrice())
                    .append(" emeralds. Say deal, no, or your counter in emeralds.");
            if (playerHasRequiredSticks) {
                fallbackReply.append(" You already have enough sticks too, so this is optional.");
            }
        } else if (sticksNearby > 0) {
            fallbackReply.append(" I only found ").append(sticksNearby).append(" stick nearby, so we still need more.");
            if (playerHasRequiredSticks) {
                fallbackReply.append(" You already have enough sticks in your inventory.");
            }
        } else {
            fallbackReply.append(" I don't have sticks in nearby chests right now.");
            if (playerHasRequiredSticks) {
                fallbackReply.append(" You already have enough sticks in your inventory.");
            }
        }
        if (coreMaterial != null) {
            String coreName = readableItemName(BuiltInRegistries.ITEM.getKey(coreMaterial).toString());
            if (coreNearby > 0) {
                fallbackReply.append(" I also found ").append(coreNearby).append(" ").append(coreName).append(" nearby.");
            } else {
                fallbackReply.append(" ").append(explorationHintForMaterial(coreMaterial, coreNeeded));
            }
        }
        fallbackReply.append(" Follow me and I'll help you gather what is missing.");

        String requiredFacts = "recipe=" + recipeSummary
                + "; player_has_sticks=" + playerHasRequiredSticks
                + "; sticks_nearby=" + sticksNearby
                + "; core_material_nearby=" + coreNearby
                + "; core_needed=" + coreNeeded
                + "; active_offer=" + (stickOffer == null ? "none" : summarizeOffer(stickOffer));
        String prompt = promptFactory.recipeAssistantPrompt(
                villager.getName().getString(),
                player.getName().getString(),
                instruction,
                requiredFacts,
                snapshot,
                memory
        );
        String responseText = parseRecipeResponseText(llmRouter.generate(prompt));
        speakAsNpc(server, villager, fallbackName, groundedRecipeText(responseText, fallbackReply.toString(), stickOffer));
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
            WorldSnapshot snapshot
    ) {
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

        ParsedTradeIntent llmClassified = classifyTradeIntentWithLlm(villager, player, instruction, session, snapshot, memory, npcId);
        ParsedTradeIntent tradeIntent = resolveTradeIntent(llmClassified, session);
        logger.info(
                "[TRADE-DEBUG] npc={} utterance='{}' llm_intent={} resolved_intent={} resolved_item={} resolved_qty={} resolved_counter={}",
                fallbackName,
                instruction,
                llmClassified.type().name().toLowerCase(Locale.ROOT),
                tradeIntent.type().name().toLowerCase(Locale.ROOT),
                tradeIntent.itemId(),
                tradeIntent.quantity(),
                tradeIntent.counterTotalPrice()
        );
        if (tradeIntent.type() == TradeIntentType.NONE && session.activeOffer == null) {
            return false;
        }
        lastTradeIntentByNpc.put(npcId, tradeIntent.type().name().toLowerCase());
        boolean engageTrade = tradeIntent.type() != TradeIntentType.NONE || session.activeOffer != null;

        // For accept/decline, don't call LLM - these are binary outcomes
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
                tradeStockSummary(villager),
                tradeFactsForIntent(tradeIntent, session, instruction),
                snapshot,
                memory
        );
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
                speakAsNpc(server, villager, fallbackName, groundedActiveOfferText(draft.responseText(), session.activeOffer));
                return true;
            }
            speakAsNpc(server, villager, fallbackName, fallbackOrDefault(draft.responseText(), "Tell me the item and amount you want to buy."));
            session.lastInteractionAtMillis = now;
            return true;
        }
        session.lastInteractionAtMillis = now;

        return switch (tradeIntent.type()) {
            case INQUIRE_STOCK -> handleStockInquiry(server, villager, fallbackName, session, tradeIntent, now, draft);
            case INQUIRE_PAYMENT -> handlePaymentInquiry(server, villager, fallbackName, draft);
            case INQUIRE_SESSION_STATUS -> handleSessionStatusInquiry(server, villager, fallbackName, session, draft);
            case REQUEST_OFFER -> handleTradeRequest(server, villager, fallbackName, session, tradeIntent, now, draft);
            case COUNTER_OFFER -> handleTradeCounter(server, villager, fallbackName, session, tradeIntent, now, draft);
            case ACCEPT_OFFER, DECLINE_OFFER -> false;  // Already handled above
            case NONE -> false;
        };
    }

    private boolean handleSessionStatusInquiry(
            MinecraftServer server,
            Villager villager,
            String fallbackName,
            TradeSession session,
            TradeNegotiationDraft draft
    ) {
        if (session.activeOffer != null) {
            speakAsNpc(server, villager, fallbackName, groundedActiveOfferText(draft.responseText(), session.activeOffer));
            return true;
        }
        speakAsNpc(server, villager, fallbackName, fallbackOrDefault(draft.responseText(), "No active offer right now."));
        return true;
    }

    private boolean handlePaymentInquiry(
            MinecraftServer server,
            Villager villager,
            String fallbackName,
            TradeNegotiationDraft draft
    ) {
        speakAsNpc(server, villager, fallbackName, fallbackOrDefault(draft.responseText(), "I only accept emeralds right now."));
        return true;
    }

    private boolean handleStockInquiry(
            MinecraftServer server,
            Villager villager,
            String fallbackName,
            TradeSession session,
            ParsedTradeIntent tradeIntent,
            long now,
            TradeNegotiationDraft draft
    ) {
        ServerLevel level = (ServerLevel) villager.level();
        String requestedItemId = tradeIntent == null ? "" : tradeIntent.itemId();
        if (requestedItemId != null && !requestedItemId.isBlank() && tradeOfferEngine.supportsItem(requestedItemId)) {
            Item requestedItem = resolveKnownItem(requestedItemId);
            if (requestedItem != null) {
                int stock = countItemInNearbyChests(level, villager.blockPosition(), requestedItem, TRADE_SCAN_RADIUS);
                session.lastRequestedItemId = requestedItemId;
                session.lastRequestedQuantity = 1;
                if (stock <= 0) {
                    speakAsNpc(server, villager, fallbackName, "I don't have that in stock right now.");
                    return true;
                }
                TradeOffer preview = tradeOfferEngine.quote(requestedItemId, 1, stock, 0, false, now);
                String response = "I currently have " + stock + " " + readableItemName(requestedItemId)
                        + " in stock at " + preview.unitPrice() + " emerald each.";
                speakAsNpc(server, villager, fallbackName, groundedStockText(draft.responseText(), response));
                return true;
            }
        }

        List<String> offers = new ArrayList<>();
        String firstItemIdWithStock = null;
        for (String itemId : tradeOfferEngine.supportedItems()) {
            Item item = resolveKnownItem(itemId);
            if (item == null) {
                continue;
            }
            int stock = countItemInNearbyChests(level, villager.blockPosition(), item, TRADE_SCAN_RADIUS);
            if (stock <= 0) {
                continue;
            }
            if (firstItemIdWithStock == null) {
                firstItemIdWithStock = itemId;
            }
            TradeOffer preview = tradeOfferEngine.quote(itemId, 1, stock, 0, false, now);
            offers.add(readableItemName(itemId) + " (" + stock + " in stock, " + preview.unitPrice() + " emerald each)");
        }
        if (offers.isEmpty()) {
            session.activeOffer = null;
            speakAsNpc(server, villager, fallbackName, "I'm out of stock right now.");
            return true;
        }
        session.lastRequestedItemId = firstItemIdWithStock;
        session.lastRequestedQuantity = 1;
        offers.sort(String::compareTo);
        int limit = Math.min(4, offers.size());
        String message = String.join(", ", offers.subList(0, limit));
        if (offers.size() > limit) {
            message = message + ", and more.";
        }
        speakAsNpc(server, villager, fallbackName, groundedStockText(draft.responseText(), "I currently sell: " + message));
        return true;
    }

    private boolean handleTradeRequest(
            MinecraftServer server,
            Villager villager,
            String fallbackName,
            TradeSession session,
            ParsedTradeIntent tradeIntent,
            long now,
            TradeNegotiationDraft draft
    ) {
        String itemId = tradeIntent.itemId();
        int quantity = Math.max(1, tradeIntent.quantity());
        if (!tradeOfferEngine.supportsItem(itemId)) {
            speakAsNpc(server, villager, fallbackName, "I don't trade that item right now.");
            return true;
        }

        int stock = countItemInNearbyChests((ServerLevel) villager.level(), villager.blockPosition(), resolveKnownItem(itemId), TRADE_SCAN_RADIUS);
        if (stock < quantity) {
            speakAsNpc(server, villager, fallbackName, "I only have " + stock + " in stock right now.");
            return true;
        }

        TradeOffer offer = tradeOfferEngine.quote(itemId, quantity, stock, 0, false, now);
        TradeNegotiationDraft pricingDraft = draft;
        if (pricingDraft != null && (pricingDraft.suggestedUnitPrice() != null || pricingDraft.suggestedTotalPrice() != null)) {
            offer = tradeOfferEngine.quote(
                    itemId,
                    quantity,
                    stock,
                    0,
                    false,
                    now,
                    pricingDraft.suggestedUnitPrice(),
                    pricingDraft.suggestedTotalPrice()
            );
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
            TradeNegotiationDraft draft
    ) {
        if (session.activeOffer == null) {
            speakAsNpc(server, villager, fallbackName, "I need to make an offer first.");
            return true;
        }
        Integer proposed = tradeIntent.counterTotalPrice();
        if (proposed == null || proposed <= 0) {
            int minimum = tradeOfferEngine.minimumAcceptableTotal(session.activeOffer);
            int autoCounter = Math.max(minimum, session.activeOffer.totalPrice() - Math.max(1, session.activeOffer.totalPrice() / 10));
            if (draft.counterTotalPrice() != null && draft.counterTotalPrice() >= minimum) {
                autoCounter = draft.counterTotalPrice();
            }
            TradeOffer autoOffer = new TradeOffer(
                    session.activeOffer.itemId(),
                    session.activeOffer.quantity(),
                    Math.max(1, autoCounter / Math.max(1, session.activeOffer.quantity())),
                    autoCounter,
                    session.activeOffer.maxDiscountPct(),
                    session.activeOffer.expiresAtMillis()
            );
            session.activeOffer = autoOffer;
            speakAsNpc(server, villager, fallbackName, groundedCounterText(draft.responseText(), autoOffer));
            return true;
        }
        TradeCounterResult counter = tradeOfferEngine.evaluateCounter(session.activeOffer, proposed, now);
        if (!counter.accepted()) {
            speakAsNpc(server, villager, fallbackName, groundedCounterRejectionText(
                    draft.responseText(),
                    counter.minimumAcceptableTotal(),
                    session.activeOffer.quantity(),
                    session.activeOffer.itemId()
            ));
            return true;
        }

        session.activeOffer = counter.offer();
        speakAsNpc(server, villager, fallbackName, groundedCounterText(draft.responseText(), counter.offer()));
        return true;
    }

    private boolean handleTradeAccept(
            MinecraftServer server,
            Villager villager,
            String fallbackName,
            ServerPlayer player,
            TradeSession session,
            TradeNegotiationDraft draft
    ) {
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
        int stock = countItemInNearbyChests(level, villager.blockPosition(), item, TRADE_SCAN_RADIUS);
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
        ItemStack collected = withdrawFromNearbyChests(level, villager.blockPosition(), item, TRADE_SCAN_RADIUS, offer.quantity());
        if (collected.getCount() < offer.quantity()) {
            player.getInventory().add(new ItemStack(Items.EMERALD, offer.totalPrice()));
            session.activeOffer = null;
            String draftText = draft == null ? null : draft.responseText();
            speakAsNpc(server, villager, fallbackName, fallbackOrDefault(draftText, "Trade failed while collecting stock. I refunded your emeralds."));
            return true;
        }

        ItemStack remaining = insertIntoPlayerInventory(player, collected.copy());
        if (!remaining.isEmpty()) {
            Containers.dropItemStack(
                    player.level(),
                    player.getX(),
                    player.getY() + 1.0,
                    player.getZ(),
                    remaining
            );
        }

        BlockPos chestPos = findNearestChest(level, villager.blockPosition(), CHEST_SCAN_RADIUS);
        ItemStack emeraldStack = new ItemStack(Items.EMERALD, offer.totalPrice());
        if (chestPos != null) {
            ItemStack emeraldRemaining = insertIntoChest(level, chestPos, emeraldStack);
            if (!emeraldRemaining.isEmpty()) {
                Containers.dropItemStack(level, villager.getX(), villager.getY() + 1.0, villager.getZ(), emeraldRemaining);
            }
        } else {
            Containers.dropItemStack(level, villager.getX(), villager.getY() + 1.0, villager.getZ(), emeraldStack);
        }

        session.activeOffer = null;
        String draftText = draft == null ? null : draft.responseText();
        speakAsNpc(server, villager, fallbackName, fallbackOrDefault(draftText, "Deal done. Pleasure trading with you."));
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

    private String tradeStockSummary(Villager villager) {
        if (!(villager.level() instanceof ServerLevel level)) {
            return "unavailable";
        }
        List<String> entries = new ArrayList<>();
        for (String itemId : tradeOfferEngine.supportedItems()) {
            Item item = resolveKnownItem(itemId);
            if (item == null) {
                continue;
            }
            int stock = countItemInNearbyChests(level, villager.blockPosition(), item, TRADE_SCAN_RADIUS);
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
            int stock = 0;
            return "request_item=" + itemId
                    + "; quantity=" + quantity
                    + "; stock=" + stock;
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
        String lower = draftText.toLowerCase();
        boolean hasItem = lower.contains(readableItemName(offer.itemId()).toLowerCase());
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
            String itemId
    ) {
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

    private String groundedRecipeText(String draftText, String fallback, TradeOffer optionalOffer) {
        if (draftText == null || draftText.isBlank()) {
            return fallback;
        }
        String lower = draftText.toLowerCase(Locale.ROOT);
        boolean hasEmeraldWhenOffer = optionalOffer == null || lower.contains("emerald");
        boolean hasOfferPriceWhenOffer = optionalOffer == null || lower.contains(String.valueOf(optionalOffer.totalPrice()));
        boolean hasOfferItemWhenOffer = optionalOffer == null || lower.contains("stick");
        if (!hasEmeraldWhenOffer || !hasOfferPriceWhenOffer || !hasOfferItemWhenOffer) {
            return fallback;
        }
        return draftText;
    }

    public boolean isTradeLlmInFlight(UUID npcId) {
        return tradeLlmInFlightByNpc.getOrDefault(npcId, false);
    }

    public String lastTradeIntent(UUID npcId) {
        return lastTradeIntentByNpc.getOrDefault(npcId, "none");
    }

    public String currentTaskPhase(UUID npcId) {
        MineToPlayerTask mineToPlayer = activeMineToPlayer.get(npcId);
        if (mineToPlayer != null) {
            return "mine_to_player:" + mineToPlayer.phase.name().toLowerCase(Locale.ROOT)
                    + " (" + mineToPlayer.minedCount + "/" + mineToPlayer.count + ")";
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
            String reasoning
    ) {
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

        BlockPos chestPos = findChestWithItem(level, villager.blockPosition(), item, CHEST_SCAN_RADIUS, count);
        if (chestPos == null) {
            notifyNearbyPlayers(server, villager, "[LLM NPC] I could not find " + itemId + " in nearby chests.");
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
                    parameters.get("z").getAsInt()
            );
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
                0.9
        );
        if (villager.distanceToSqr(targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5) > 4.0) {
            return moving;
        }

        boolean removed = level.removeBlock(targetPos, false);
        if (removed) {
            notifyNearbyPlayers(server, villager, "[LLM NPC] Mined block at " + targetPos.getX() + ", " + targetPos.getY() + ", " + targetPos.getZ() + ".");
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

        BlockPos chestPos = findNearestChest(level, villager.blockPosition(), CHEST_SCAN_RADIUS);
        if (chestPos == null) {
            notifyNearbyPlayers(server, villager, "[LLM NPC] I could not find a nearby chest for storage.");
            return false;
        }

        MineToChestTask task = new MineToChestTask(block, minedItem, blockId, count, chestPos);
        activeMineToChest.put(npcId, task);
        notifyNearbyPlayers(server, villager, "[LLM NPC] Mining " + count + "x " + blockId + " and storing in chest.");
        return true;
    }

    private boolean startMineToPlayerTask(MinecraftServer server, UUID npcId, Villager villager, JsonObject parameters) {
        if (!(villager.level() instanceof ServerLevel)) {
            return false;
        }
        MineToPlayerTask existing = activeMineToPlayer.get(npcId);
        if (existing != null && existing.phase != MineToPlayerPhase.DONE) {
            notifyNearbyPlayers(server, villager, "[LLM NPC] I am already mining " + existing.count + "x " + existing.blockId + " for delivery.");
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
        notifyNearbyPlayers(server, villager, "[LLM NPC] Mining " + count + "x " + blockId + " and delivering to your inventory.");
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
                        0.9
                );
                double dist = villager.distanceToSqr(
                        task.targetPos.getX() + 0.5,
                        task.targetPos.getY() + 0.5,
                        task.targetPos.getZ() + 0.5
                );
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
                        0.85
                );
                double dist = villager.distanceToSqr(
                        task.chestPos.getX() + 0.5,
                        task.chestPos.getY() + 0.5,
                        task.chestPos.getZ() + 0.5
                );
                if (dist <= 3.0) {
                    task.phase = MineToChestPhase.DEPOSIT_TO_CHEST;
                }
                yield moving || dist <= 3.0;
            }
            case DEPOSIT_TO_CHEST -> {
                if (task.heldStack.isEmpty()) {
                    yield false;
                }
                ItemStack remaining = insertIntoChest(level, task.chestPos, task.heldStack);
                if (!remaining.isEmpty()) {
                    task.heldStack = remaining;
                    notifyNearbyPlayers(server, villager, "[LLM NPC] Chest is full. Could not store all mined blocks.");
                    yield false;
                }
                notifyNearbyPlayers(server, villager, "[LLM NPC] Stored " + task.minedCount + "x " + task.blockId + " in chest.");
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
                    task.targetPos = findNearestBlock(level, villager.blockPosition(), task.block, task.searchRadius, task.blockedTargets);
                }
                if (task.targetPos == null) {
                    if (task.searchRadius < 48) {
                        task.searchRadius = Math.min(48, task.searchRadius + 6);
                        yield true;
                    }
                    if (!task.heldStack.isEmpty()) {
                        notifyNearbyPlayers(server, villager, "[LLM NPC] Could only mine " + task.minedCount + "x " + task.blockId + ". Delivering what I have.");
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
                        0.9
                );
                double dist = villager.distanceToSqr(
                        task.targetPos.getX() + 0.5,
                        task.targetPos.getY() + 0.5,
                        task.targetPos.getZ() + 0.5
                );
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
                        notifyNearbyPlayers(server, villager, "[LLM NPC] I could not path to enough " + task.blockId + " to finish the order.");
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
                    notifyNearbyPlayers(server, villager, "[LLM NPC] Progress: mined " + task.minedCount + "/" + task.count + " " + task.blockId + ".");
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
                                task.heldStack.copy()
                        );
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
                            task.heldStack.copy()
                    );
                    task.heldStack = ItemStack.EMPTY;
                    notifyNearbyPlayers(server, villager, "[LLM NPC] Target player unavailable. Dropped mined items nearby.");
                    yield false;
                }
                ItemStack remaining = insertIntoPlayerInventory(player, task.heldStack.copy());
                int deliveredCount = task.heldStack.getCount() - remaining.getCount();
                if (deliveredCount > 0) {
                    notifyNearbyPlayers(server, villager, "[LLM NPC] Added " + deliveredCount + "x " + task.blockId + " to your inventory.");
                }
                if (!remaining.isEmpty()) {
                    Containers.dropItemStack(
                            player.level(),
                            player.getX(),
                            player.getY() + 1.0,
                            player.getZ(),
                            remaining
                    );
                    notifyNearbyPlayers(server, villager, "[LLM NPC] Inventory full. Dropped remaining " + remaining.getCount() + "x " + task.blockId + ".");
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

    private boolean hasActiveWork(UUID npcId) {
        if (activeMineToPlayer.containsKey(npcId) || activeMineToChest.containsKey(npcId) || activeDeliveries.containsKey(npcId)) {
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
                        0.85
                );
                double dist = villager.distanceToSqr(
                        task.chestPos.getX() + 0.5,
                        task.chestPos.getY() + 0.5,
                        task.chestPos.getZ() + 0.5
                );
                if (dist <= 3.0) {
                    task.phase = DeliveryPhase.WITHDRAW_FROM_CHEST;
                }
                yield moving || dist <= 3.0;
            }
            case WITHDRAW_FROM_CHEST -> {
                ItemStack collected = withdrawFromChest(level, task.chestPos, task.item, task.count);
                if (collected.isEmpty()) {
                    notifyNearbyPlayers(server, villager, "[LLM NPC] Could not retrieve " + task.itemId + " from chest.");
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
                        task.heldStack.copy()
                );
                notifyNearbyPlayers(server, villager, "[LLM NPC] Delivered " + task.heldStack.getCount() + "x " + task.itemId + ".");
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
            for (int dy = -4; dy <= 4; dy++) {
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

    private BlockPos findNearestChest(ServerLevel level, BlockPos center, int radius) {
        BlockPos bestPos = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
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

    private ItemStack insertIntoChest(ServerLevel level, BlockPos chestPos, ItemStack stack) {
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

    private BlockPos findNearestBlock(ServerLevel level, BlockPos center, Block block, int radius, List<BlockPos> excluded) {
        BlockPos bestPos = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
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

    private Item resolveKnownItem(String itemId) {
        return switch (itemId) {
            case "minecraft:glass", "glass", "minecraft:glass_block", "glass_block" -> Items.GLASS;
            case "minecraft:grass_block", "grass_block" -> Blocks.GRASS_BLOCK.asItem();
            case "minecraft:oak_planks", "oak_planks" -> Items.OAK_PLANKS;
            case "minecraft:cobblestone", "cobblestone" -> Items.COBBLESTONE;
            case "minecraft:oak_log", "oak_log" -> Items.OAK_LOG;
            case "minecraft:stone", "stone" -> Blocks.STONE.asItem();
            case "minecraft:dirt", "dirt" -> Blocks.DIRT.asItem();
            case "minecraft:sand", "sand" -> Blocks.SAND.asItem();
            case "minecraft:stick", "stick", "sticks" -> Items.STICK;
            case "minecraft:diamond", "diamond", "diamonds" -> Items.DIAMOND;
            case "minecraft:iron_ingot", "iron_ingot", "iron ingot", "iron", "iron ingots" -> Items.IRON_INGOT;
            default -> null;
        };
    }

    private Block resolveKnownBlock(String blockId) {
        return switch (blockId) {
            case "minecraft:grass_block", "grass_block" -> Blocks.GRASS_BLOCK;
            case "minecraft:glass", "glass", "minecraft:glass_block", "glass_block" -> Blocks.GLASS;
            case "minecraft:oak_log", "oak_log" -> Blocks.OAK_LOG;
            case "minecraft:stone", "stone" -> Blocks.STONE;
            case "minecraft:dirt", "dirt" -> Blocks.DIRT;
            case "minecraft:sand", "sand" -> Blocks.SAND;
            case "minecraft:cobblestone", "cobblestone" -> Blocks.COBBLESTONE;
            case "minecraft:diamond_ore", "diamond_ore", "diamond ore" -> Blocks.DIAMOND_ORE;
            case "minecraft:deepslate_diamond_ore", "deepslate_diamond_ore", "deepslate diamond ore" -> Blocks.DEEPSLATE_DIAMOND_ORE;
            default -> null;
        };
    }

    private boolean isRecipeQuery(String lower) {
        return (lower.contains("recipe") || lower.contains("craft") || lower.contains("make"))
                && (lower.contains("what do i need") || lower.contains("how do i") || lower.contains("what do i need to"));
    }

    private Item recipeTargetItem(String lower) {
        if (lower.contains("diamond pickaxe") || lower.contains("diamond pick")) {
            return Items.DIAMOND_PICKAXE;
        }
        if (lower.contains("iron pickaxe") || lower.contains("iron pick")) {
            return Items.IRON_PICKAXE;
        }
        return null;
    }

    private String summarizeRecipeFromGame(MinecraftServer server, Item outputItem) {
        // Best-effort recipe presence check from the active recipe manager.
        // Ingredient extraction APIs differ across mappings, so we keep stable summaries for known targets.
        boolean recipeExists = hasRecipeForOutput(server, outputItem);
        if (outputItem == Items.DIAMOND_PICKAXE) {
            return recipeExists ? "3 diamonds and 2 sticks" : "3 diamonds and 2 sticks";
        }
        if (outputItem == Items.IRON_PICKAXE) {
            return recipeExists ? "3 iron ingots and 2 sticks" : "3 iron ingots and 2 sticks";
        }
        return "the required crafting ingredients";
    }

    private Item recipeCoreMaterial(Item outputItem) {
        if (outputItem == Items.DIAMOND_PICKAXE) {
            return Items.DIAMOND;
        }
        if (outputItem == Items.IRON_PICKAXE) {
            return Items.IRON_INGOT;
        }
        return null;
    }

    private int requiredCoreCount(Item outputItem) {
        if (outputItem == Items.DIAMOND_PICKAXE || outputItem == Items.IRON_PICKAXE) {
            return 3;
        }
        return 0;
    }

    private String explorationHintForMaterial(Item material, int neededCount) {
        if (material == Items.DIAMOND) {
            return "Diamonds are usually deep underground around deepslate levels. We still need about " + neededCount + ".";
        }
        if (material == Items.IRON_INGOT) {
            return "Iron is common in caves and mountains. We still need about " + neededCount + " iron ingots.";
        }
        return "We still need more materials for that recipe.";
    }

    private boolean hasRecipeForOutput(MinecraftServer server, Item outputItem) {
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
                return false;
            }

            for (Object holder : iterable) {
                Object recipe = invokeIfPresent(holder, "value");
                if (recipe == null) {
                    recipe = holder;
                }
                ItemStack result = extractRecipeResult(server, recipe);
                if (result != null && !result.isEmpty() && result.is(outputItem)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
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
            if (player.level() == villager.level() && player.distanceToSqr(villager) <= 400.0) {
                player.sendSystemMessage(message);
            }
        }
    }

    private int countItemInNearbyChests(ServerLevel level, BlockPos center, Item item, int radius) {
        if (item == null) {
            return 0;
        }
        int total = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    BlockEntity blockEntity = level.getBlockEntity(pos);
                    if (!(blockEntity instanceof ChestBlockEntity chest)) {
                        continue;
                    }
                    for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                        ItemStack stack = chest.getItem(slot);
                        if (stack.is(item)) {
                            total += stack.getCount();
                        }
                    }
                }
            }
        }
        return total;
    }

    private ItemStack withdrawFromNearbyChests(ServerLevel level, BlockPos center, Item item, int radius, int neededCount) {
        int remaining = neededCount;
        ItemStack collected = ItemStack.EMPTY;
        for (int dx = -radius; dx <= radius && remaining > 0; dx++) {
            for (int dy = -4; dy <= 4 && remaining > 0; dy++) {
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
            Villager villager,
            ServerPlayer player,
            String instruction,
            TradeSession session,
            WorldSnapshot snapshot,
            MemoryContext memory,
            UUID npcId
    ) {
        String prompt = promptFactory.tradeIntentClassifierPrompt(
                villager.getName().getString(),
                player.getName().getString(),
                instruction,
                session.activeOffer == null ? "" : summarizeOffer(session.activeOffer),
                tradeStockSummary(villager),
                snapshot,
                memory
        );
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
                        tradeStockSummary(villager),
                        session.lastRequestedItemId
                );
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
                    0.7
            );
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
                rawClassified == null ? "" : rawClassified.replace('\n', ' ').replace('\r', ' ')
        );
        if (TRADE_DEBUG_CHAT) {
            player.sendSystemMessage(Component.literal(String.format(
                    Locale.ROOT,
                    "[LLM NPC][DEBUG] trade-intent LLM: intent=%s confidence=%.2f item=%s qty=%d counter=%s",
                    classified.intent().name().toLowerCase(Locale.ROOT),
                    classified.confidence(),
                    classified.itemId() == null || classified.itemId().isBlank() ? "-" : classified.itemId(),
                    classified.quantity(),
                    classified.counterTotalPrice() == null ? "-" : classified.counterTotalPrice()
            )));
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
                    TRADE_INTENT_CONFIDENCE_THRESHOLD
            );
        }
        if (classified.intent() == TradeIntentType.ACCEPT_OFFER && session.activeOffer == null) {
            logger.info("[TRADE-DEBUG] npc={} trade_intent_llm rejected: accept_offer without active offer", villager.getName().getString());
            return ParsedTradeIntent.none();
        }
        if (classified.intent() == TradeIntentType.COUNTER_OFFER && session.activeOffer == null) {
            logger.info("[TRADE-DEBUG] npc={} trade_intent_llm rejected: counter_offer without active offer", villager.getName().getString());
            return ParsedTradeIntent.none();
        }
        String itemId = classified.itemId();
        if ((classified.intent() == TradeIntentType.REQUEST_OFFER || classified.intent() == TradeIntentType.INQUIRE_STOCK)
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
                && (llmIntent.type() == TradeIntentType.REQUEST_OFFER || llmIntent.type() == TradeIntentType.INQUIRE_STOCK)
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
                counter
        );
    }

    private void notifyNearbyPlayers(MinecraftServer server, Villager villager, String text) {
        Component message = Component.literal(text);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level() == villager.level() && player.distanceToSqr(villager) <= 400.0) {
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
}
