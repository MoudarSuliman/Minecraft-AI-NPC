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
        JsonObject payload = new JsonObject();
        payload.addProperty("role", "You are an embodied Minecraft NPC. Return JSON only.");
        payload.add("perception", snapshot.toJson());
        payload.add("memory", memoryToJson(memory));
        payload.addProperty("has_pending_instruction", hasPendingInstruction);
        payload.addProperty("latest_instruction", latestInstruction);
        payload.addProperty("expected_intent", expectedIntent);
        payload.addProperty("target_hint", targetHint);
        payload.addProperty("required_schema",
                        "{\"intent\":\"idle|dialogue_reply|recipe_reply|move_to|fetch_from_chest|mine_block|mine_to_chest|mine_to_player|trade_offer|trade_accept|trade_decline|trade_counter|place_block|break_block|build_structure\",\"parameters\":{},\"reasoning\":\"...\",\"priority\":0.0}");
        payload.addProperty("constraints", hasPendingInstruction
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
        return payload.toString();
    }

    public String dialoguePrompt(String playerText, WorldSnapshot snapshot, MemoryContext memory) {
        JsonObject payload = new JsonObject();
        payload.addProperty("task", "dialogue");
        payload.addProperty("player_utterance", playerText);
        payload.add("perception", snapshot.toJson());
        payload.add("memory", memoryToJson(memory));
        payload.addProperty("format", "{\"intent\":\"dialogue_reply\",\"parameters\":{\"text\":\"...\"},\"reasoning\":\"...\",\"priority\":0.0}");
        return payload.toString();
    }

    public String planningPrompt(String objective, WorldSnapshot snapshot, MemoryContext memory) {
        JsonObject payload = new JsonObject();
        payload.addProperty("task", "planning");
        payload.addProperty("objective", objective);
        payload.add("perception", snapshot.toJson());
        payload.add("memory", memoryToJson(memory));
        payload.addProperty("format",
                "{\"intent\":\"build_structure\",\"parameters\":{\"steps\":[{\"intent\":\"move_to\",\"parameters\":{}},{\"intent\":\"place_block\",\"parameters\":{}}]},\"reasoning\":\"...\",\"priority\":0.0}");
        return payload.toString();
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
        JsonObject payload = new JsonObject();
        payload.addProperty("task", "trade_negotiation");
        payload.addProperty("npc_name", npcName);
        payload.addProperty("player_name", playerName);
        payload.addProperty("player_utterance", playerText);
        payload.addProperty("mode", mode);
        payload.addProperty("active_offer", activeOfferSummary);
        payload.addProperty("stock_summary", stockSummary);
        payload.addProperty("required_facts", requiredFacts);
        payload.add("perception", snapshot.toJson());
        payload.add("memory", memoryToJson(memory));
        payload.addProperty("format",
                "{\"response_text\":\"...\",\"suggested_unit_price\":1,\"suggested_total_price\":2,\"counter_total_price\":123,\"reasoning\":\"...\",\"priority\":0.0}");
        payload.addProperty("constraints",
                "Return strict JSON only. Keep the text natural, brief, and consistent with required_facts. "
                        + "CRITICAL: Only use 'emeralds' for payment currency - NEVER use 'gold', 'coins', or other currencies. "
                        + "Never invent items, prices, or stock. Suggested prices must be integers >= 1. "
                        + "Always reference the actual stock and item names from required_facts. "
                        + "If suggesting a new price, set both suggested_unit_price and suggested_total_price. "
                        + "If counter_total_price is not relevant, omit it or set null.");
        return payload.toString();
    }

    public String recipeAssistantPrompt(
            String npcName,
            String playerName,
            String playerText,
            String requiredFacts,
            WorldSnapshot snapshot,
            MemoryContext memory
    ) {
        JsonObject payload = new JsonObject();
        payload.addProperty("task", "recipe_assistant");
        payload.addProperty("npc_name", npcName);
        payload.addProperty("player_name", playerName);
        payload.addProperty("player_utterance", playerText);
        payload.addProperty("required_facts", requiredFacts);
        payload.add("perception", snapshot.toJson());
        payload.add("memory", memoryToJson(memory));
        payload.addProperty("format", "{\"response_text\":\"...\"}");
        payload.addProperty("constraints",
                "Return strict JSON only. Keep response_text concise (max 2 sentences) and natural. "
                        + "Do not invent quantities, items, stock, or prices not in required_facts. "
                        + "You MUST include the recipe requirements from required_facts. "
                        + "You MUST include the nearby ingredient status from required_facts, but only describe what is actually present. "
                        + "If required_facts active_offer is \"none\", do NOT claim you can trade or provide items from stock. "
                        + "If required_facts includes an active_offer (not \"none\"), you MUST mention the exact offer with emerald currency and ask if the player wants to trade.");
        return payload.toString();
    }

    public String recipeIntentClassifierPrompt(
            String npcName,
            String playerName,
            String playerText
    ) {
        JsonObject payload = new JsonObject();
        payload.addProperty("task", "recipe_intent_classifier");
        payload.addProperty("npc_name", npcName);
        payload.addProperty("player_name", playerName);
        payload.addProperty("player_utterance", playerText);
        payload.addProperty("format", "{\"is_recipe\":true,\"confidence\":0.0}");
        payload.addProperty("constraints",
                "Return strict JSON only. "
                        + "Set is_recipe=true only when the player is asking about crafting, recipes, or how to make an item (e.g. \\\"what is the recipe for a diamond pickaxe\\\", \\\"how do I craft a furnace\\\"). "
                        + "Set is_recipe=false for trade requests, stock questions, or unrelated chat.");
        return payload.toString();
    }

    public String recipeTargetResolverPrompt(
            String npcName,
            String playerName,
            String playerText
    ) {
        JsonObject payload = new JsonObject();
        payload.addProperty("task", "recipe_target_resolution");
        payload.addProperty("npc_name", npcName);
        payload.addProperty("player_name", playerName);
        payload.addProperty("player_utterance", playerText);
        payload.addProperty("format", "{\"target_phrase\":\"...\",\"item_id\":\"minecraft:...\",\"confidence\":0.0}");
        payload.addProperty("constraints",
                "Return strict JSON only. "
                        + "Extract the intended craftable target from player_utterance. "
                        + "Set target_phrase to a short noun phrase like 'diamond pickaxe'. "
                        + "Set item_id only if reasonably sure (minecraft namespace). "
                        + "If uncertain, keep item_id empty and still provide best target_phrase. "
                        + "Do not include extra text.");
        return payload.toString();
    }

    public String relationshipGreetingPrompt(
            String npcName,
            String playerName,
            MemoryContext memory
    ) {
        JsonObject payload = new JsonObject();
        payload.addProperty("task", "relationship_greeting");
        payload.addProperty("npc_name", npcName);
        payload.addProperty("player_name", playerName);
        payload.add("memory", memoryToJson(memory));
        payload.addProperty("format", "{\"response_text\":\"...\"}");
        payload.addProperty("constraints",
                "Return strict JSON only. Keep response_text short (max 2 sentences), warm, and natural. "
                        + "The greeting MUST start with 'Welcome back'. "
                        + "Pick one meaningful topic from memory.long_term that reflects a concrete prior task, objective, or event. "
                        + "Avoid trivial confirmations like 'yes', 'ok', 'deal', 'sure', or generic trade chatter. "
                        + "Reference the chosen topic as something discussed last time without inventing new facts. "
                        + "Do not mention internal memory or JSON.");
        return payload.toString();
    }

    public String environmentalAwarenessPrompt(
            String npcName,
            String playerName,
            WorldSnapshot snapshot,
            MemoryContext memory
    ) {
        JsonObject payload = new JsonObject();
        payload.addProperty("task", "environmental_advisory");
        payload.addProperty("npc_name", npcName);
        payload.addProperty("player_name", playerName == null || playerName.isBlank() ? "player" : playerName);
        payload.add("perception", snapshot.toJson());
        payload.add("memory", memoryToJson(memory));
        payload.addProperty("format", "{\"response_text\":\"...\", \"severity\":\"low|medium|high\", \"evidence\":[]}");
        payload.addProperty("constraints",
                "Return strict JSON only. Produce a concise, proactive environment-aware advisory (max 2 short sentences). "
                        + "ONLY mention entities, hazards, or features that are directly supported by the provided 'perception' JSON. "
                        + "Do NOT invent or infer absent entities, mobs, or events. If you are unsure, return severity 'low' and response_text empty. "
                        + "Classification rules: set severity='high' only when evidence includes hostile mobs present (zombie, creeper, skeleton, warden, blaze, pillager, vindicator, phantom, enderman) OR threat_level=='high'. "
                        + "Set severity='medium' when evidence includes at least one hostile mob within 16 blocks or environmental hazards (low light + hostile nearby) or biome-specific dangerous mobs (e.g., 'end' + shulker present). "
                        + "Set severity='low' for passive mobs, decorative items, plants, trader llamas, or item entities (e.g., 'Pink Petals'). If evidence contains only non-hostile entities/items, return severity 'low' and response_text empty. "
                        + "Include an 'evidence' array listing zero-or-more facts copied verbatim from perception that justify the advisory (examples: 'nearby_entities:zombie', 'biome:end', 'threat_level:high'). "
                        + "Only return a non-empty response_text when severity is 'medium' or 'high'. "
                        + "Return strict JSON in this exact format: {\"response_text\":\"...\", \"severity\":\"medium|high|low\", \"evidence\":[\"...\"]}. "
                        + "Do NOT suggest trades, inventory, prices, or cause the NPC to take actions. If nothing noteworthy, return response_text empty and severity 'low'.");
        return payload.toString();
    }

    public String searchStatusPrompt(
            String npcName,
            String playerName,
            String eventType,
            String requiredFacts
    ) {
        JsonObject payload = new JsonObject();
        payload.addProperty("task", "search_status_dialogue");
        payload.addProperty("npc_name", npcName);
        payload.addProperty("player_name", playerName);
        payload.addProperty("event_type", eventType);
        payload.addProperty("required_facts", requiredFacts);
        payload.addProperty("format", "{\"response_text\":\"...\"}");
        payload.addProperty("constraints",
                "Return strict JSON only. Keep response_text to one short sentence (max 16 words). "
                + "Use first-person NPC speech (no system tags). "
                + "Do not include role labels or narration like 'As a villager'. "
                + "Do not ask follow-up questions. "
                + "Do not invent coordinates or targets not in required_facts. "
                + "If coordinates are in required_facts, repeat them accurately. "
                + "For search_start acknowledge the target and promise to report back. "
                + "For target_found you MUST explicitly say the target was found and include the exact location. "
                + "For beacon_start/beacon_update mention the exact target and location. "
                + "For search_expand mention the new search radius. "
                + "For search_failed explicitly say it was not found. "
                + "For search_cancelled explicitly say the search is cancelled. "
                + "For search_complete explicitly say the search is complete.");
        return payload.toString();
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
        JsonObject payload = new JsonObject();
        payload.addProperty("task", "trade_intent_classifier");
        payload.addProperty("npc_name", npcName);
        payload.addProperty("player_name", playerName);
        payload.addProperty("player_utterance", playerText);
        payload.addProperty("active_offer", activeOfferSummary);
        payload.addProperty("stock_summary", stockSummary);
        payload.add("perception", snapshot.toJson());
        payload.add("memory", memoryToJson(memory));
        payload.addProperty("format",
                "{\"intent\":\"none|inquire_stock|inquire_payment|inquire_session_status|request_offer|accept_offer|decline_offer|counter_offer\","
                        + "\"item_id\":\"minecraft:...\",\"quantity\":1,\"counter_total_price\":1,\"confidence\":0.0}");
        payload.addProperty("constraints",
                "Return strict JSON only. Choose exactly one intent. Use confidence 0..1. "
                        + "Treat availability/count/price/payment questions as trade intents. "
                        + "Examples that should map to inquire_stock: 'do you have sticks', 'how many sticks do you have', 'what do you sell'. "
                        + "Location/availability follow-ups like 'where are the sticks', 'where can I find sticks', "
                        + "'but you said you had sticks' MUST map to inquire_stock, not request_offer. "
                        + "Examples that should map to request_offer: 'can I get 2 sticks', 'offer for 3 glass'. "
                        + "request_offer only when player explicitly asks to buy/get/trade now. Recipe/crafting/how-to-make questions (e.g. 'what is the recipe for a diamond pickaxe') are NOT trade; return intent none. "
                        + "If active_offer is present and player indicates agreement (deal/yes/sure/go ahead/take it), map to accept_offer. "
                        + "If active_offer is present and player rejects (no/nope/not now), map to decline_offer. "
                        + "For explicit emerald counter proposals, map to counter_offer and set counter_total_price. "
                        + "Use intent none only when the utterance is truly unrelated to trade. "
                        + "Never invent item ids; only use known Minecraft item ids from stock_summary or active_offer.");
        return payload.toString();
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
                + "- 'where are X', 'where can i find X', 'you said you had X' => inquire_stock.\n"
                + "- 'can I get X', 'give me X', 'I want X' => request_offer.\n- 'what is the recipe for X', 'how do I craft X', 'how to make X' => none (not trade).\n"
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
                + "- Use inquire_stock for location/availability follow-ups (e.g. 'where are the sticks', 'you said you had sticks').\n"
                + "- Use inquire_session_status for clarification/confusion about the current offer or last trade statement (e.g. 'what does that mean', 'can you explain', 'why 2 emeralds', 'what do you mean').\n- Use none for recipe/crafting/how-to-make questions (e.g. 'what is the recipe for a diamond pickaxe').\n"
                + "- Do NOT use request_offer for clarification or stock/location follow-ups.\n"
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



