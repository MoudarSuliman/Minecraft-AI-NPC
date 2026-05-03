package com.example;

import com.example.ai.runtime.AutonomousNpcRuntime;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;

public class ExampleMod implements ModInitializer {
	public static final String MOD_ID = "llm_npc";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private AutonomousNpcRuntime runtime;

	@Override
	public void onInitialize() {
		runtime = AutonomousNpcRuntime.createDefault(LOGGER);
		ServerTickEvents.END_SERVER_TICK.register(runtime::onServerTick);
		registerCommands();
		LOGGER.info("LLM NPC runtime initialized.");
	}

	private void registerCommands() {
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> {
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
		});
	}

	private int bindNearestVillager(ServerPlayer player) {
		Villager nearest = findNearestVillager(player, 20.0);
		if (nearest == null) {
			player.sendSystemMessage(Component.literal("[LLM NPC] No villager found within 20 blocks."));
			return 0;
		}

		String displayName = nearest.getName().getString();
		runtime.registerAgent(nearest.getUUID(), displayName);
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

	private Villager findNearestVillager(ServerPlayer player, double radius) {
		if (!(player.level() instanceof ServerLevel world)) {
			player.sendSystemMessage(Component.literal("[LLM NPC] Could not access server world."));
			return null;
		}
		AABB searchBox = new AABB(player.blockPosition()).inflate(radius);
		List<Villager> villagers = world.getEntitiesOfClass(Villager.class, searchBox, Entity::isAlive);
		if (villagers.isEmpty()) {
			return null;
		}

		return villagers.stream()
				.min(Comparator.comparingDouble(v -> v.distanceToSqr(player)))
				.orElseThrow();
	}
}
