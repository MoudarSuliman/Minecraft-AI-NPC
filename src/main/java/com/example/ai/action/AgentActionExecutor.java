package com.example.ai.action;

import com.example.ai.intent.AgentDecision;
import com.example.ai.llm.LlmRouter;
import com.example.ai.memory.MemoryContext;
import com.example.ai.perception.WorldSnapshot;
import com.example.ai.prompt.PromptFactory;
import com.example.ai.trade.ParsedTradeIntent;
import com.example.ai.trade.TradeNegotiationDraft;
import com.example.ai.trade.TradeNegotiationParser;
import com.example.ai.trade.TradeCounterResult;
import com.example.ai.trade.TradeIntentParser;
import com.example.ai.trade.TradeIntentType;
import com.example.ai.trade.TradeOffer;
import com.example.ai.trade.TradeOfferEngine;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AgentActionExecutor {
    private static final Pattern FOR_BLOCKS_COUNT_PATTERN = Pattern.compile("\\bfor\\s+(\\d+)\\s+blocks?\\b");
    private static final Pattern LEADING_BLOCK_COUNT_PATTERN = Pattern.compile("\\b(\\d+)\\s+[a-z_]+\\s+blocks?\\b");
    private static final Pattern EMERALD_AMOUNT_PATTERN = Pattern.compile("\\b(\\d+)\\s+emeralds?\\b");
    private static final int CHEST_SCAN_RADIUS = 16;
    private static final int MINE_SCAN_RADIUS = 8;
    private static final int TRADE_SCAN_RADIUS = 18;
    private static final long TRADE_GREETING_COOLDOWN_MILLIS = 20_000L;
    private static final long TRADE_RECENT_INTERACTION_SUPPRESS_MILLIS = 45_000L;
    private static final double OWNER_IDLE_RADIUS_SQR = 36.0;   // 6 blocks
    private static final double OWNER_PULL_RADIUS_SQR = 196.0;  // 14 blocks
    private static final double OWNER_TELEPORT_RADIUS_SQR = 3600.0; // 60 blocks
    private static final double TRADE_GREETING_RADIUS_SQR = 25.0; // 5 blocks

    private final Logger logger;
    private final PromptFactory promptFactory;
    private final LlmRouter llmRouter;
    private final TradeOfferEngine tradeOfferEngine = TradeOfferEngine.defaultEngine();
    private final TradeIntentParser tradeIntentParser = new TradeIntentParser();
    private final TradeNegotiationParser tradeNegotiationParser = new TradeNegotiationParser();
    private final Map<UUID, List<JsonObject>> actionOutbox = new ConcurrentHashMap<>();
    private final Map<UUID, DeliveryTask> activeDeliveries = new ConcurrentHashMap<>();
    private final Map<UUID, MineToChestTask> activeMineToChest = new ConcurrentHashMap<>();
    private final Map<UUID, MineToPlayerTask> activeMineToPlayer = new ConcurrentHashMap<>();
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

        ParsedTradeIntent tradeIntent = tradeIntentParser.parse(instruction);
        if (tradeIntent.type() == TradeIntentType.INQUIRE_STOCK
                && isBarePriceInquiry(instruction)
                && session.lastRequestedItemId != null
                && !session.lastRequestedItemId.isBlank()) {
            tradeIntent = new ParsedTradeIntent(TradeIntentType.REQUEST_OFFER, session.lastRequestedItemId, 1, null);
        }
        if (tradeIntent.type() == TradeIntentType.COUNTER_OFFER
                && session.activeOffer == null
                && session.lastRequestedItemId != null
                && !session.lastRequestedItemId.isBlank()) {
            int quantity = extractRequestedQuantityFromCounterPhrase(instruction);
            tradeIntent = new ParsedTradeIntent(TradeIntentType.REQUEST_OFFER, session.lastRequestedItemId, quantity, null);
        }
        if (tradeIntent.type() == TradeIntentType.NONE && session.activeOffer != null) {
            Integer explicitCounter = extractEmeraldCounterAmount(instruction);
            if (explicitCounter != null) {
                tradeIntent = new ParsedTradeIntent(TradeIntentType.COUNTER_OFFER, "", 0, explicitCounter);
            }
        }
        boolean engageTrade = tradeIntent.type() != TradeIntentType.NONE
                || session.activeOffer != null
                || isLikelyTradeUtterance(instruction)
                || isTradeFollowUpUtterance(instruction, session);
        if (!engageTrade) {
            return false;
        }

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
        TradeNegotiationDraft draft = tradeNegotiationParser.parse(llmRouter.generate(prompt));
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
            case INQUIRE_STOCK -> handleStockInquiry(server, villager, fallbackName, session, now, draft);
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
            long now,
            TradeNegotiationDraft draft
    ) {
        ServerLevel level = (ServerLevel) villager.level();
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
        if (!hasMinimum || !hasCurrency || hasNpcPrefix || givesPlayerEmeralds) {
            return fallback;
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
        String fallback = "We still have an active offer: " + summarizeOffer(offer) + ". Say deal, no, or your counter in emeralds.";
        if (draftText == null || draftText.isBlank()) {
            return fallback;
        }
        String lower = draftText.toLowerCase();
        boolean hasPrice = lower.contains(String.valueOf(offer.totalPrice()));
        boolean hasCurrency = lower.contains("emerald");
        boolean hasItem = lower.contains(readableItemName(offer.itemId()).toLowerCase());
        return hasPrice && hasCurrency && hasItem ? draftText : fallback;
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
                    task.targetPos = findNearestBlock(level, villager.blockPosition(), task.block, MINE_SCAN_RADIUS);
                }
                if (task.targetPos == null) {
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
                }
                yield moving || dist <= 4.0;
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
                task.phase = MineToPlayerPhase.DONE;
                task.heldStack = ItemStack.EMPTY;
                yield false;
            }
            case DONE -> false;
        };
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
        BlockPos bestPos = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (!level.getBlockState(pos).is(block)) {
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
            default -> null;
        };
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

    private boolean isLikelyTradeUtterance(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase();
        return lower.contains("buy")
                || lower.contains("sell")
                || lower.contains("trade")
                || lower.contains("emerald")
                || lower.contains("offer")
                || lower.contains("deal")
                || lower.contains("price")
                || lower.contains("stock")
                || lower.contains("how")
                || lower.contains("much")
                || lower.contains("cost")
                || lower.contains("charge");
    }

    private boolean isBarePriceInquiry(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase();
        return (lower.contains("how much") || lower.contains("what price") || lower.contains("cost"))
                && !lower.contains("buy")
                && !lower.contains("give me")
                && !lower.contains("can i get");
    }

    private boolean isTradeFollowUpUtterance(String text, TradeSession session) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (session == null) {
            return false;
        }
        long age = System.currentTimeMillis() - session.lastInteractionAtMillis;
        if (age > 30_000L) {
            return false;
        }
        String trimmed = text.trim().toLowerCase();
        return trimmed.equals("yes")
                || trimmed.equals("okay")
                || trimmed.equals("ok")
                || trimmed.equals("deal")
                || trimmed.equals("sure")
                || trimmed.contains("that's what i said")
                || trimmed.contains("thats what i said");
    }

    private int extractRequestedQuantityFromCounterPhrase(String text) {
        if (text == null || text.isBlank()) {
            return 1;
        }
        String lower = text.toLowerCase();
        Matcher matcher = FOR_BLOCKS_COUNT_PATTERN.matcher(lower);
        if (matcher.find()) {
            try {
                return Math.max(1, Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException ignored) {
                return 1;
            }
        }
        matcher = LEADING_BLOCK_COUNT_PATTERN.matcher(lower);
        if (matcher.find()) {
            try {
                return Math.max(1, Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException ignored) {
                return 1;
            }
        }
        return 1;
    }

    private Integer extractEmeraldCounterAmount(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = EMERALD_AMOUNT_PATTERN.matcher(text.toLowerCase());
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return null;
        }
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
