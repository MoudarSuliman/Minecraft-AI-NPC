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
                "{\"intent\":\"idle|dialogue_reply|move_to|fetch_from_chest|mine_block|mine_to_chest|mine_to_player|trade_offer|trade_accept|trade_decline|trade_counter|place_block|break_block|build_structure\",\"parameters\":{},\"reasoning\":\"...\",\"priority\":0.0}");
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

    public String tradeNegotiationPrompt(
            String npcName,
            String playerName,
            String playerText,
            String mode,
            String activeOfferSummary,
            String stockSummary,
            String requiredFacts,
            WorldSnapshot snapshot,
            MemoryContext memory
    ) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("task", "trade_negotiation");
        envelope.addProperty("npc_name", npcName);
        envelope.addProperty("player_name", playerName);
        envelope.addProperty("player_utterance", playerText);
        envelope.addProperty("mode", mode);
        envelope.addProperty("active_offer", activeOfferSummary);
        envelope.addProperty("stock_summary", stockSummary);
        envelope.addProperty("required_facts", requiredFacts);
        envelope.add("perception", snapshot.toJson());
        envelope.add("memory", memoryToJson(memory));
        envelope.addProperty("format",
                "{\"response_text\":\"...\",\"suggested_unit_price\":1,\"suggested_total_price\":2,\"counter_total_price\":123,\"reasoning\":\"...\",\"priority\":0.0}");
        envelope.addProperty("constraints",
                "Return strict JSON only. Keep the text natural, brief, and consistent with required_facts. "
                        + "CRITICAL: Only use 'emeralds' for payment currency - NEVER use 'gold', 'coins', or other currencies. "
                        + "Never invent items, prices, or stock. Suggested prices must be integers >= 1. "
                        + "Always reference the actual stock and item names from required_facts. "
                        + "If suggesting a new price, set both suggested_unit_price and suggested_total_price. "
                        + "If counter_total_price is not relevant, omit it or set null.");
        return envelope.toString();
    }

    public String recipeAssistantPrompt(
            String npcName,
            String playerName,
            String playerText,
            String requiredFacts,
            WorldSnapshot snapshot,
            MemoryContext memory
    ) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("task", "recipe_assistant");
        envelope.addProperty("npc_name", npcName);
        envelope.addProperty("player_name", playerName);
        envelope.addProperty("player_utterance", playerText);
        envelope.addProperty("required_facts", requiredFacts);
        envelope.add("perception", snapshot.toJson());
        envelope.add("memory", memoryToJson(memory));
        envelope.addProperty("format", "{\"response_text\":\"...\"}");
        envelope.addProperty("constraints",
                "Return strict JSON only. Keep response_text concise and natural. "
                        + "Do not invent quantities, items, stock, or prices not in required_facts. "
                        + "If required_facts includes an active_offer, mention the exact offer and emerald currency.");
        return envelope.toString();
    }

    public String tradeIntentClassifierPrompt(
            String npcName,
            String playerName,
            String playerText,
            String activeOfferSummary,
            String stockSummary,
            WorldSnapshot snapshot,
            MemoryContext memory
    ) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("task", "trade_intent_classifier");
        envelope.addProperty("npc_name", npcName);
        envelope.addProperty("player_name", playerName);
        envelope.addProperty("player_utterance", playerText);
        envelope.addProperty("active_offer", activeOfferSummary);
        envelope.addProperty("stock_summary", stockSummary);
        envelope.add("perception", snapshot.toJson());
        envelope.add("memory", memoryToJson(memory));
        envelope.addProperty("format",
                "{\"intent\":\"none|inquire_stock|inquire_payment|inquire_session_status|request_offer|accept_offer|decline_offer|counter_offer\","
                        + "\"item_id\":\"minecraft:...\",\"quantity\":1,\"counter_total_price\":1,\"confidence\":0.0}");
        envelope.addProperty("constraints",
                "Return strict JSON only. Choose exactly one intent. Use confidence 0..1. "
                        + "Treat availability/count/price/payment questions as trade intents. "
                        + "Examples that should map to inquire_stock: 'do you have sticks', 'how many sticks do you have', 'what do you sell'. "
                        + "Examples that should map to request_offer: 'can I get 2 sticks', 'offer for 3 glass'. "
                        + "If active_offer is present and player indicates agreement (deal/yes/sure/go ahead/take it), map to accept_offer. "
                        + "If active_offer is present and player rejects (no/nope/not now), map to decline_offer. "
                        + "For explicit emerald counter proposals, map to counter_offer and set counter_total_price. "
                        + "Use intent none only when the utterance is truly unrelated to trade. "
                        + "Never invent item ids; only use known Minecraft item ids from stock_summary or active_offer.");
        return envelope.toString();
    }

    public String tradeIntentRecoveryPrompt(
            String playerText,
            String activeOfferSummary,
            String stockSummary,
            String lastRequestedItemId
    ) {
        String safePlayerText = playerText == null ? "" : playerText;
        String safeActiveOffer = activeOfferSummary == null ? "" : activeOfferSummary;
        String safeStockSummary = stockSummary == null ? "" : stockSummary;
        String safeLastItem = lastRequestedItemId == null ? "" : lastRequestedItemId;

        return "You are a Minecraft trade intent classifier.\n"
                + "Classify the player's trade intent.\n"
                + "Player utterance: " + safePlayerText + "\n"
                + "Active offer: " + safeActiveOffer + "\n"
                + "Stock summary: " + safeStockSummary + "\n"
                + "Last requested item id: " + safeLastItem + "\n"
                + "Rules:\n"
                + "- 'do you have X', 'how many X', 'what do you sell' => inquire_stock.\n"
                + "- 'can I get X', 'give me X', 'I want X' => request_offer.\n"
                + "- active offer + 'deal/yes/sure' => accept_offer.\n"
                + "- active offer + 'no/nope/not now' => decline_offer.\n"
                + "- explicit emerald counter => counter_offer with counter_total_price.\n"
                + "- If follow-up omits item and last requested item exists, reuse it.\n"
                + "- Use none only if unrelated to trade.\n"
                + "Return ONLY this JSON object with no extra text:\n"
                + "{\"intent\":\"none|inquire_stock|inquire_payment|inquire_session_status|request_offer|accept_offer|decline_offer|counter_offer\","
                + "\"item_id\":\"minecraft:...\",\"quantity\":1,\"counter_total_price\":1,\"confidence\":0.0}";
    }

    public String tradeIntentDisambiguationPrompt(
            String playerText,
            String activeOfferSummary,
            String stockSummary,
            String lastRequestedItemId,
            String initialIntent,
            String initialItemId,
            int initialQuantity,
            Integer initialCounterTotalPrice,
            double initialConfidence
    ) {
        String safePlayerText = playerText == null ? "" : playerText;
        String safeActiveOffer = activeOfferSummary == null ? "" : activeOfferSummary;
        String safeStockSummary = stockSummary == null ? "" : stockSummary;
        String safeLastItem = lastRequestedItemId == null ? "" : lastRequestedItemId;
        String safeInitialIntent = initialIntent == null ? "none" : initialIntent;
        String safeInitialItem = initialItemId == null ? "" : initialItemId;
        String safeInitialCounter = initialCounterTotalPrice == null ? "null" : String.valueOf(initialCounterTotalPrice);

        return "You are a Minecraft trade intent classifier.\n"
                + "Decide the single best trade intent for the player's message.\n"
                + "Player utterance: " + safePlayerText + "\n"
                + "Active offer: " + safeActiveOffer + "\n"
                + "Stock summary: " + safeStockSummary + "\n"
                + "Last requested item id: " + safeLastItem + "\n"
                + "Initial classification: intent=" + safeInitialIntent
                + ", item_id=" + safeInitialItem
                + ", quantity=" + initialQuantity
                + ", counter_total_price=" + safeInitialCounter
                + ", confidence=" + initialConfidence + "\n"
                + "Rules:\n"
                + "- Use request_offer when player asks to receive/get/buy now (e.g. 'give me 1 stick', 'can I get 2 sticks', 'i want 1 stick').\n"
                + "- Use inquire_stock only for availability/count questions (e.g. 'do you have sticks', 'how many sticks').\n"
                + "- If follow-up omits item and last requested item id exists, reuse it.\n"
                + "- Use none only if unrelated to trade.\n"
                + "Return ONLY this JSON object with no extra text:\n"
                + "{\"intent\":\"none|inquire_stock|inquire_payment|inquire_session_status|request_offer|accept_offer|decline_offer|counter_offer\","
                + "\"item_id\":\"minecraft:...\",\"quantity\":1,\"counter_total_price\":1,\"confidence\":0.0}";
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
