package com.example.ai.prompt;

import com.example.ai.memory.MemoryContext;
import com.example.ai.memory.MemoryEntry;
import com.example.ai.perception.WorldSnapshot;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public final class PromptFactory {
    public String actionSelectionPrompt(
            WorldSnapshot snapshot,
            MemoryContext memory,
            boolean hasPendingInstruction,
            String latestInstruction,
            String expectedIntent,
            String targetHint
    ) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("role", "You are an embodied Minecraft NPC. Return JSON only.");
        envelope.add("perception", snapshot.toJson());
        envelope.add("memory", memoryToJson(memory));
        envelope.addProperty("has_pending_instruction", hasPendingInstruction);
        envelope.addProperty("latest_instruction", latestInstruction);
        envelope.addProperty("expected_intent", expectedIntent);
        envelope.addProperty("target_hint", targetHint);
        envelope.addProperty("required_schema",
                "{\"intent\":\"idle|dialogue_reply|move_to|fetch_from_chest|mine_block|mine_to_chest|mine_to_player|place_block|break_block|build_structure\",\"parameters\":{},\"reasoning\":\"...\",\"priority\":0.0}");
        envelope.addProperty("constraints", hasPendingInstruction
                ? "No destructive behavior, stay near NPC, respect safety, output strict JSON only with no markdown. "
                + "If has_pending_instruction is true, you MUST NOT return idle. "
                + "Choose an actionable intent that advances latest_instruction. "
                + "If latest_instruction asks to bring/fetch an item, use intent fetch_from_chest with "
                + "parameters {\"item_id\":\"minecraft:...\",\"count\":1}. "
                + "If latest_instruction asks to mine a block, use intent mine_block with "
                + "parameters {\"block\":\"minecraft:...\"}. "
                + "If latest_instruction asks to mine and put/store in chest, use intent mine_to_chest with "
                + "parameters {\"block\":\"minecraft:...\",\"count\":1}. "
                + "If latest_instruction asks to mine and give to player, use intent mine_to_player with "
                + "parameters {\"block\":\"minecraft:...\",\"count\":1}. "
                + "If expected_intent is non-empty, you MUST set intent exactly to expected_intent. "
                + "If target_hint is non-empty, use it in parameters.block or parameters.item_id. "
                + "For dialogue_reply, parameters.text is REQUIRED."
                : "No destructive behavior, stay near NPC, respect safety, output strict JSON only with no markdown.");
        return envelope.toString();
    }

    public String dialoguePrompt(String playerText, WorldSnapshot snapshot, MemoryContext memory) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("task", "dialogue");
        envelope.addProperty("player_utterance", playerText);
        envelope.add("perception", snapshot.toJson());
        envelope.add("memory", memoryToJson(memory));
        envelope.addProperty("format", "{\"intent\":\"dialogue_reply\",\"parameters\":{\"text\":\"...\"},\"reasoning\":\"...\",\"priority\":0.0}");
        return envelope.toString();
    }

    public String planningPrompt(String objective, WorldSnapshot snapshot, MemoryContext memory) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("task", "planning");
        envelope.addProperty("objective", objective);
        envelope.add("perception", snapshot.toJson());
        envelope.add("memory", memoryToJson(memory));
        envelope.addProperty("format",
                "{\"intent\":\"build_structure\",\"parameters\":{\"steps\":[{\"intent\":\"move_to\",\"parameters\":{}},{\"intent\":\"place_block\",\"parameters\":{}}]},\"reasoning\":\"...\",\"priority\":0.0}");
        return envelope.toString();
    }

    private JsonObject memoryToJson(MemoryContext memory) {
        JsonObject json = new JsonObject();
        json.add("short_term", toArray(memory.shortTerm()));
        json.add("working", toArray(memory.working()));
        json.add("long_term", toArray(memory.longTerm()));
        return json;
    }

    private JsonArray toArray(Iterable<MemoryEntry> entries) {
        JsonArray array = new JsonArray();
        for (MemoryEntry entry : entries) {
            array.add(entry.toJson());
        }
        return array;
    }
}
