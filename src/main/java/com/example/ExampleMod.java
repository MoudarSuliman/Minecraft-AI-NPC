package com.example;

import com.example.ai.runtime.AutonomousNpcRuntime;
import com.example.ai.trade.PriceConfig;
import com.example.ai.trade.TradePriceStore;
import com.example.network.OpenTradePricesPayload;
import com.example.network.SaveTradePricesPayload;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class ExampleMod implements ModInitializer {
	public static final String MOD_ID = "llm_npc";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private AutonomousNpcRuntime runtime;

	@Override
	public void onInitialize() {
		runtime = AutonomousNpcRuntime.createDefault(LOGGER);
		ServerTickEvents.END_SERVER_TICK.register(runtime::onServerTick);

		PayloadTypeRegistry.clientboundPlay().register(OpenTradePricesPayload.TYPE, OpenTradePricesPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(SaveTradePricesPayload.TYPE, SaveTradePricesPayload.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(SaveTradePricesPayload.TYPE, (payload, context) -> {
			context.server().execute(() -> {
				try {
					Map<String, PriceConfig> prices = TradePriceStore.fromJson(payload.pricesJson());
					runtime.updateTradePrices(prices);
					TradePriceStore.save(prices);
					context.player().sendSystemMessage(
							Component.literal("[LLM NPC] Trade prices saved (" + prices.size() + " items)."));
				} catch (Exception e) {
					LOGGER.error("[LLM NPC] Failed to save trade prices", e);
				}
			});
		});

		registerCommands();
		LOGGER.info("LLM NPC runtime initialized.");
	}

	private void registerCommands() {
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> {
			dispatcher.register(
					Commands.literal("llm_spawn")
							.executes(context -> spawnAndBindVillager(
									context.getSource().getPlayerOrException(), null))
							.then(Commands.argument("name", StringArgumentType.greedyString())
									.executes(context -> spawnAndBindVillager(
											context.getSource().getPlayerOrException(),
											StringArgumentType.getString(context, "name"))))
			);
			dispatcher.register(
					Commands.literal("llm_bind_nearest")
							.executes(context -> bindNearestVillager(context.getSource().getPlayerOrException()))
			);
			dispatcher.register(
					Commands.literal("llm_tell")
							.then(Commands.argument("message", StringArgumentType.greedyString())
									.executes(context -> tellNearestVillager(
											context.getSource().getPlayerOrException(),
											StringArgumentType.getString(context, "message")
									)))
			);
			dispatcher.register(
					Commands.literal("llm_status")
							.executes(context -> statusNearestVillager(context.getSource().getPlayerOrException()))
			);
			dispatcher.register(
					Commands.literal("llm_debug")
							.executes(context -> debugNearestVillager(context.getSource().getPlayerOrException()))
			);
			dispatcher.register(
					Commands.literal("llm_trade_prices")
							.executes(context -> openTradePrices(context.getSource().getPlayerOrException()))
			);
		});
	}

	private int openTradePrices(ServerPlayer player) {
		String json = TradePriceStore.toJson(runtime.currentTradePriceConfigs());
		ServerPlayNetworking.send(player, new OpenTradePricesPayload(json));
		return 1;
	}

	private int spawnAndBindVillager(ServerPlayer player, String name) {
		if (!(player.level() instanceof ServerLevel level)) {
			player.sendSystemMessage(Component.literal("[LLM NPC] Could not access server world."));
			return 0;
		}
		Villager villager = new Villager(EntityType.VILLAGER, level);
		villager.teleportTo(player.getX(), player.getY(), player.getZ());
		if (name != null && !name.isBlank()) {
			villager.setCustomName(Component.literal(name));
			villager.setCustomNameVisible(true);
		}
		level.addFreshEntity(villager);
		String displayName = villager.getName().getString();
		runtime.registerAgent(villager.getUUID(), displayName, player.getUUID());
		player.sendSystemMessage(Component.literal("[LLM NPC] Spawned and bound villager: " + displayName));
		return 1;
	}

	private int bindNearestVillager(ServerPlayer player) {
		Villager nearest = findNearestVillager(player, 20.0);
		if (nearest == null) {
			player.sendSystemMessage(Component.literal("[LLM NPC] No villager found within 20 blocks."));
			return 0;
		}
		String displayName = nearest.getName().getString();
		runtime.registerAgent(nearest.getUUID(), displayName, player.getUUID());
		player.sendSystemMessage(Component.literal("[LLM NPC] Bound nearest villager: " + displayName));
		return 1;
	}

	private int tellNearestVillager(ServerPlayer player, String message) {
		Villager nearest = findNearestVillager(player, 20.0);
		if (nearest == null) {
			player.sendSystemMessage(Component.literal("[LLM NPC] No villager found within 20 blocks."));
			return 0;
		}
		if (!runtime.isAgentRegistered(nearest.getUUID())) {
			player.sendSystemMessage(Component.literal("[LLM NPC] Nearest villager is not bound. Use /llm_bind_nearest first."));
			return 0;
		}
		runtime.enqueuePlayerUtterance(nearest.getUUID(), player.getName().getString(), message);
		player.sendSystemMessage(Component.literal("[LLM NPC] Instruction queued for " + nearest.getName().getString() + ": " + message));
		return 1;
	}

	private int statusNearestVillager(ServerPlayer player) {
		Villager nearest = findNearestVillager(player, 20.0);
		if (nearest == null) {
			player.sendSystemMessage(Component.literal("[LLM NPC] No villager found within 20 blocks."));
			return 0;
		}
		if (!runtime.isAgentRegistered(nearest.getUUID())) {
			player.sendSystemMessage(Component.literal("[LLM NPC] Nearest villager is not bound. Use /llm_bind_nearest first."));
			return 0;
		}
		player.sendSystemMessage(Component.literal("[LLM NPC][STATUS] " + runtime.statusForAgent(nearest.getUUID())));
		return 1;
	}

	private int debugNearestVillager(ServerPlayer player) {
		Villager nearest = findNearestVillager(player, 20.0);
		if (nearest == null) {
			player.sendSystemMessage(Component.literal("[LLM NPC] No villager found within 20 blocks."));
			return 0;
		}
		if (!runtime.isAgentRegistered(nearest.getUUID())) {
			player.sendSystemMessage(Component.literal("[LLM NPC] Nearest villager is not bound. Use /llm_bind_nearest first."));
			return 0;
		}
		for (String line : runtime.debugForAgent(nearest.getUUID())) {
			player.sendSystemMessage(Component.literal("[LLM NPC][DEBUG] " + line));
		}
		return 1;
	}

	private Villager findNearestVillager(ServerPlayer player, double radius) {
		if (!(player.level() instanceof ServerLevel world)) {
			player.sendSystemMessage(Component.literal("[LLM NPC] Could not access server world."));
			return null;
		}
		AABB searchBox = new AABB(player.blockPosition()).inflate(radius);
		List<Villager> villagers = world.getEntitiesOfClass(Villager.class, searchBox, Entity::isAlive);
		if (villagers.isEmpty()) return null;
		return villagers.stream()
				.min(Comparator.comparingDouble(v -> v.distanceToSqr(player)))
				.orElseThrow();
	}
}
