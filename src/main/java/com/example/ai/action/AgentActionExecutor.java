package com.example.ai.action;

import com.example.ai.intent.AgentDecision;
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

public final class AgentActionExecutor {
    private static final int CHEST_SCAN_RADIUS = 16;
    private static final int MINE_SCAN_RADIUS = 8;

    private final Logger logger;
    private final Map<UUID, List<JsonObject>> actionOutbox = new ConcurrentHashMap<>();
    private final Map<UUID, DeliveryTask> activeDeliveries = new ConcurrentHashMap<>();
    private final Map<UUID, MineToChestTask> activeMineToChest = new ConcurrentHashMap<>();
    private final Map<UUID, MineToPlayerTask> activeMineToPlayer = new ConcurrentHashMap<>();

    public AgentActionExecutor(Logger logger) {
        this.logger = logger;
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
                    yield false;
                }
                boolean moving = villager.getNavigation().moveTo(player.getX(), player.getY(), player.getZ(), 0.85);
                if (villager.distanceToSqr(player) <= 9.0) {
                    task.phase = MineToPlayerPhase.GIVE_TO_PLAYER;
                }
                yield moving || villager.distanceToSqr(player) <= 9.0;
            }
            case GIVE_TO_PLAYER -> {
                ServerPlayer player = server.getPlayerList().getPlayer(task.targetPlayerId);
                if (player == null || task.heldStack.isEmpty()) {
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
