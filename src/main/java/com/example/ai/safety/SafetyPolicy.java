package com.example.ai.safety;

import com.example.ai.intent.AgentDecision;
import com.example.ai.intent.AgentIntentType;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SafetyPolicy {
    private final Set<String> deniedBlocks;
    private final long actionCooldownMillis;
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    private SafetyPolicy(Set<String> deniedBlocks, long actionCooldownMillis) {
        this.deniedBlocks = deniedBlocks;
        this.actionCooldownMillis = actionCooldownMillis;
    }

    public static SafetyPolicy defaultPolicy() {
        return new SafetyPolicy(Set.of("minecraft:tnt", "minecraft:lava", "minecraft:fire"), 500L);
    }

    public boolean allow(UUID npcId, AgentDecision decision) {
        long now = System.currentTimeMillis();
        long allowedAt = cooldowns.getOrDefault(npcId, 0L);
        if (now < allowedAt) {
            return false;
        }

        if (decision.intent() == AgentIntentType.PLACE_BLOCK || decision.intent() == AgentIntentType.BREAK_BLOCK) {
            String block = decision.parameters().has("block")
                    ? decision.parameters().get("block").getAsString()
                    : "";
            if (deniedBlocks.contains(block)) {
                return false;
            }
        }

        cooldowns.put(npcId, now + actionCooldownMillis);
        return true;
    }
}
