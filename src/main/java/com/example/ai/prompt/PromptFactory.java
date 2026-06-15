package com.example.ai.prompt;

import com.example.ai.memory.MemoryContext;
import com.example.ai.memory.MemoryEntry;
import com.example.ai.perception.WorldSnapshot;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Map;

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
                        "{\"intent\":\"idle|dialogue_reply|recipe_reply|scout_explorer|move_to|fetch_from_chest|mine_block|mine_to_chest|mine_to_player|trade_offer|trade_accept|trade_decline|trade_counter|place_block|break_block|build_structure\",\"parameters\":{},\"reasoning\":\"...\",\"priority\":0.0}");
        payload.addProperty("constraints", hasPendingInstruction
                ? "No destructive behavior, stay near NPC, respect safety, output strict JSON only with no markdown. "
                + "If has_pending_instruction is true, you MUST NOT return idle. "
                + "IMPORTANT — intent selection rules in priority order:\n"
                + "1. Any message that is a QUESTION or asks about your abilities, knowledge, or nature MUST use dialogue_reply — "
                + "   this overrides ALL other rules including expected_intent. "
                + "   Examples: 'do you have the ability to mine?' -> dialogue_reply. 'can you build?' -> dialogue_reply. "
                + "   'you there?' -> dialogue_reply. 'hello' -> dialogue_reply. 'hey' -> dialogue_reply. "
                + "   'how are you' -> dialogue_reply. 'what are you doing' -> dialogue_reply.\n"
                + "2. If latest_instruction asks to build, construct, place, or create a structure or floor or wall of blocks, "
                + "use intent build_structure with parameters {\"description\":\"...\"}. "
                + "Examples: 'build a 3x3 cobblestone floor' -> build_structure. 'construct a wall' -> build_structure.\n"
                + "3. If latest_instruction asks to bring/fetch an item, use intent fetch_from_chest with "
                + "parameters {\"item_id\":\"minecraft:...\",\"count\":1}.\n"
                + "4. If latest_instruction asks to mine a block, use intent mine_block with "
                + "parameters {\"block\":\"minecraft:...\"}.\n"
                + "5. If latest_instruction asks to mine and put/store in chest, use intent mine_to_chest with "
                + "parameters {\"block\":\"minecraft:...\",\"count\":1}.\n"
                + "6. If latest_instruction asks to mine and give to player, use intent mine_to_player with "
                + "parameters {\"block\":\"minecraft:...\",\"count\":1}.\n"
                + "7. If the player wants to buy, sell, trade, exchange, or make a deal for items, use trade_offer. "
                + "If the player accepts a trade offer, use trade_accept. "
                + "If the player rejects or declines, use trade_decline. "
                + "If the player proposes different terms or a counter-offer, use trade_counter. "
                + "Examples: 'I want to buy wood' -> trade_offer. 'deal' -> trade_accept. 'no thanks' -> trade_decline. 'how about 2 emeralds instead?' -> trade_counter. "
                + "NEVER use dialogue_reply for trade requests — always use a trade_* intent.\n"
                + "8. scout_explorer ONLY when latest_instruction contains explicit travel/exploration verbs: "
                + "'go', 'scout', 'explore', 'check out', 'head to', 'travel', 'report back after going'. "
                + "Parameters: {\"direction\":\"north|south|east|west|forward|around\",\"distance\":48,\"focus\":\"biome|structures|hostiles|resources|anything\",\"return_report\":true}. "
                + "NEVER use scout_explorer for greetings, questions, build requests, vague replies ('ok', 'use those'), or anything without a travel verb.\n"
                + "9. If expected_intent is non-empty, you MUST set intent exactly to expected_intent.\n"
                + "10. If target_hint is non-empty, use it in parameters.block or parameters.item_id.\n"
                + "11. For dialogue_reply, parameters.text MUST be the NPC's own reply in its own words — "
                + "NEVER repeat or echo the player's message. "
                + "Example: player says 'you there?' -> parameters.text = 'Yes, I am here. What do you need?' "
                + "Example: player says 'hello' -> parameters.text = 'Hello there! How can I help you?'"
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

    public String scoutIntentPrompt(
            String npcName,
            String playerName,
            String playerText,
            WorldSnapshot snapshot,
            MemoryContext memory) {
        String direction = "around";
        String lower = playerText == null ? "" : playerText.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("north")) direction = "north";
        else if (lower.contains("south")) direction = "south";
        else if (lower.contains("east")) direction = "east";
        else if (lower.contains("west")) direction = "west";
        return "Classify this Minecraft player instruction.\n"
                + "Player (" + playerName + ") said to NPC (" + npcName + "): \"" + playerText + "\"\n"
                + "\n"
                + "RULE: Only classify as scout_explorer if the player is explicitly asking the NPC to physically\n"
                + "TRAVEL to a location, look around there, and COME BACK to report. Everything else is idle.\n"
                + "\n"
                + "idle examples (NOT scouting):\n"
                + "  'you there?' -> idle\n"
                + "  'hey' -> idle\n"
                + "  'hello' -> idle\n"
                + "  'are you there?' -> idle\n"
                + "  'what are you doing?' -> idle\n"
                + "  'do you have sticks' -> idle\n"
                + "  'how much does glass cost' -> idle\n"
                + "  'craft me a pickaxe' -> idle\n"
                + "  'can you help me' -> idle\n"
                + "\n"
                + "scout_explorer examples (scouting ONLY):\n"
                + "  'go check out east and let me know what you see' -> scout_explorer, direction=east\n"
                + "  'go check out east' -> scout_explorer, direction=east\n"
                + "  'scout north for me' -> scout_explorer, direction=north\n"
                + "  'go look around and tell me what you find' -> scout_explorer, direction=around\n"
                + "  'explore to the west' -> scout_explorer, direction=west\n"
                + "  'go see what is over there and report back' -> scout_explorer, direction=around\n"
                + "\n"
                + "The player said: \"" + playerText + "\"\n"
                + "Does this instruction require the NPC to physically TRAVEL somewhere and come back? "
                + (lower.contains("go") || lower.contains("scout") || lower.contains("explore") || lower.contains("check out")
                        ? "The message contains a movement/scout verb."
                        : "The message does NOT contain a movement or scouting verb — default to idle.")
                + "\n\n"
                + "Return ONLY valid JSON, no extra text.\n"
                + "If this is NOT a scouting request (the default):\n"
                + "{\"intent\":\"idle\",\"parameters\":{},\"reasoning\":\"not a scout request\",\"priority\":0.0}\n"
                + "If this IS explicitly a scouting request:\n"
                + "{\"intent\":\"scout_explorer\",\"parameters\":{\"direction\":\"" + direction + "\",\"distance\":48,\"focus\":\"anything\",\"return_report\":true},\"reasoning\":\"player asked to explore\",\"priority\":0.8}";
    }

    public String scoutReportPrompt(
            String npcName,
            String playerName,
            String objective,
            String direction,
            String distance,
            String surveyFacts) {
        JsonObject payload = new JsonObject();
        payload.addProperty("task", "scout_report");
        payload.addProperty("npc_name", npcName);
        payload.addProperty("player_name", playerName);
        payload.addProperty("objective", objective);
        payload.addProperty("direction", direction);
        payload.addProperty("distance", distance);
        payload.addProperty("survey_facts", surveyFacts);
        payload.addProperty("role", "You are a Minecraft NPC who just returned from a scouting mission. Your ONLY job is to report what you observed.");
        payload.addProperty("format", "{\"response_text\":\"I went <direction> about <distance> blocks. <what you observed based on survey_facts>.\"}");
        payload.addProperty("constraints",
                "You MUST return ONLY this exact JSON structure with a single key: {\"response_text\":\"...\"}. "
                        + "Do NOT use 'intent', 'parameters', 'reasoning', 'priority', or any other key. "
                        + "Fill response_text with 1-3 short first-person NPC sentences. "
                        + "Begin by stating the direction and approximate distance you traveled. "
                        + "Then describe what was found using ONLY the facts in survey_facts: mention biome, threat level, notable entities, and block types. "
                        + "If the area was calm and safe, say so plainly. "
                        + "Do not invent locations, structures, mobs, or events not present in survey_facts.");
        return payload.toString();
    }

    public String scoutStatusPrompt(
            String npcName,
            String playerName,
            String eventType,
            String requiredFacts) {
        JsonObject payload = new JsonObject();
        payload.addProperty("task", "scout_status_dialogue");
        payload.addProperty("npc_name", npcName);
        payload.addProperty("player_name", playerName);
        payload.addProperty("event_type", eventType);
        payload.addProperty("required_facts", requiredFacts);
        payload.addProperty("format", "{\"response_text\":\"...\"}");
        payload.addProperty("constraints",
                        "Return strict JSON only. Keep response_text to one short sentence. "
                                + "Use first-person NPC speech. "
                                + "Do not invent locations, hostiles, or structures. "
                                + "For scout_start acknowledge the plan and say you will report back. "
                                + "For scout_return mention that you are coming back or have returned. "
                                + "For scout_abort say the scout was aborted. "
                                + "For scout_complete state the scouting is complete. "
                                + "Only use facts supplied in required_facts.");
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
                        + "IMPORTANT - instructions asking the NPC to physically GO somewhere and look around are NOT trade; return intent none. "
                        + "Examples that MUST return none: "
                        + "'go check out east and let me know what you see' => none; "
                        + "'go check out east' => none; "
                        + "'scout north' => none; "
                        + "'go look around' => none; "
                        + "'go see what is there' => none; "
                        + "'explore that area' => none; "
                        + "'go and report back' => none. "
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
                + "- 'scout X', 'explore ahead', 'check the area', 'report back', 'go check out east', 'go look around', 'go see what is there', 'go check out X and let me know', 'go have a look' => none (not trade, it is a scouting request).\n"
                + "- active offer + 'deal/yes/sure' => accept_offer.\n"
                + "- active offer + 'no/nope/not now' => decline_offer.\n"
                + "- explicit emerald counter => counter_offer with counter_total_price.\n"
                + "- If follow-up omits item and last requested item exists, reuse it.\n"
                + "- Use none only if unrelated to trade.\n"
                + "- Scout/explore/check area instructions are not trade.\n"
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

    public String dialogueReplyPrompt(String npcName, String playerName, String playerText,
            WorldSnapshot snapshot, MemoryContext memory) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a Minecraft NPC named ").append(npcName).append(". Reply naturally to the player.\n");
        sb.append("Player (").append(playerName).append(") said: \"").append(playerText).append("\"\n");

        if (snapshot != null) {
            if (snapshot.npcPosition() != null) {
                sb.append("NPC position: x=").append((int) snapshot.npcPosition().x())
                  .append(" y=").append((int) snapshot.npcPosition().y())
                  .append(" z=").append((int) snapshot.npcPosition().z()).append("\n");
            }
            if (snapshot.nearbyEntities() != null && !snapshot.nearbyEntities().isEmpty()) {
                sb.append("Nearby entities (what I can see right now):\n");
                for (WorldSnapshot.SeenEntity e : snapshot.nearbyEntities()) {
                    sb.append("  - ").append(e.name()).append(" (").append(e.type()).append(")")
                      .append(" at ").append(String.format("%.1f", e.distance())).append(" blocks\n");
                }
            } else {
                sb.append("Nearby entities: none visible\n");
            }
            if (snapshot.nearbyBlockHistogram() != null && !snapshot.nearbyBlockHistogram().isEmpty()) {
                sb.append("Nearby blocks (counts within ~8 blocks):\n");
                snapshot.nearbyBlockHistogram().entrySet().stream()
                        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                        .limit(8)
                        .forEach(e -> sb.append("  - ").append(e.getKey()).append(": ").append(e.getValue()).append("\n"));
            }
            if (snapshot.environment() != null) {
                sb.append("Time: ").append(snapshot.environment().isNight() ? "night" : "day")
                  .append(", weather: ").append(snapshot.environment().weather())
                  .append(", biome: ").append(snapshot.environment().biome())
                  .append(", threat level: ").append(snapshot.environment().threatLevel()).append("\n");
            }
        }

        if (memory != null && !memory.longTerm().isEmpty()) {
            sb.append("Things I remember from past interactions:\n");
            for (MemoryEntry e : memory.longTerm()) {
                sb.append("  [memory] ").append(e.content()).append("\n");
            }
        }
        if (memory != null && !memory.shortTerm().isEmpty()) {
            sb.append("Recent conversation and events (most recent first):\n");
            int start = Math.max(0, memory.shortTerm().size() - 6);
            for (int i = memory.shortTerm().size() - 1; i >= start; i--) {
                sb.append("  ").append(memory.shortTerm().get(i).content()).append("\n");
            }
        }

        sb.append("My abilities (what I can actually do when commanded):\n");
        sb.append("  - Mine blocks (mine_block, mine and store in chest, mine and deliver to player)\n");
        sb.append("  - Fetch items from nearby chests and bring them to the player\n");
        sb.append("  - Build structures (floors, walls, simple constructions)\n");
        sb.append("  - Scout and explore in a direction and report back\n");
        sb.append("  - Trade items with the player\n");
        sb.append("  - Answer questions and hold a conversation\n");
        sb.append("IMPORTANT RULES:\n");
        sb.append("- If asked about your abilities or what you can do: answer using ONLY the abilities listed above. Do not invent new ones.\n");
        sb.append("- If asked about surroundings (entities, weather, blocks): answer ONLY from the world data above. Do not invent.\n");
        sb.append("- If asked what tasks you have done, completed, or remember: look in 'Things I remember from past interactions' above. List them directly and specifically. Do NOT say 'I don't remember' if entries exist.\n");
        sb.append("- If asked about what was just said: use the recent conversation section.\n");
        sb.append("- Never invent facts, events, or task names not present in the data above.\n");
        sb.append("Keep the reply short (1-2 sentences), friendly, and in character as a villager.\n");
        sb.append("NEVER repeat the player's words back. Reply in your own words.\n");
        sb.append("Return ONLY this JSON with no extra text:\n");
        sb.append("{\"text\":\"your reply here\"}");
        return sb.toString();
    }

    public String searchRequestPrompt(String playerText, MemoryContext memory) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are helping a Minecraft NPC understand a player's request.\n\n");
        // Only include recent context when the instruction contains a pronoun that needs resolving
        String lower = playerText == null ? "" : playerText.toLowerCase(java.util.Locale.ROOT);
        boolean hasPronoun = lower.contains(" it") || lower.contains(" that") || lower.contains(" there")
                || lower.contains(" one") || lower.contains(" them");
        if (hasPronoun && memory != null && !memory.shortTerm().isEmpty()) {
            sb.append("Recent conversation (use ONLY to resolve pronouns like 'it', 'that', 'there'):\n");
            int start = Math.max(0, memory.shortTerm().size() - 4);
            for (int i = start; i < memory.shortTerm().size(); i++) {
                sb.append("  ").append(memory.shortTerm().get(i).content()).append("\n");
            }
            sb.append("\n");
        }
        sb.append("Player said: \"").append(playerText).append("\"\n\n");
        sb.append("TASK: Decide if the player is explicitly asking the NPC to GO and find a specific creature.\n\n");
        sb.append("RULE: Return is_search=true ONLY when the instruction contains a clear action verb\n");
        sb.append("(find, locate, search for, look for, lead me to, take me to, show me, where is,\n");
        sb.append("guide me to, bring me to) AND names or refers to a specific creature.\n");
        sb.append("If either is missing, return is_search=false.\n\n");
        sb.append("EXAMPLES — NOT a search (return {\"is_search\": false}):\n");
        sb.append("  'how r u doing' → {\"is_search\": false}\n");
        sb.append("  'what's up?' → {\"is_search\": false}\n");
        sb.append("  'hello' → {\"is_search\": false}\n");
        sb.append("  'it is a nice day' → {\"is_search\": false}\n");
        sb.append("  'are there any enemies nearby?' → {\"is_search\": false}\n");
        sb.append("  'do you see any mobs?' → {\"is_search\": false}\n");
        sb.append("  'did you find it?' → {\"is_search\": false}\n");
        sb.append("  'any luck?' → {\"is_search\": false}\n\n");
        sb.append("EXAMPLES — IS a search:\n");
        sb.append("  'find me a chicken' → {\"is_search\": true, \"entity_label\": \"a chicken\", \"entity_id\": \"minecraft:chicken\"}\n");
        sb.append("  'locate a sheep' → {\"is_search\": true, \"entity_label\": \"a sheep\", \"entity_id\": \"minecraft:sheep\"}\n");
        sb.append("  'lead me to the nearest cow' → {\"is_search\": true, \"entity_label\": \"a cow\", \"entity_id\": \"minecraft:cow\"}\n");
        sb.append("  'search for a zombie' → {\"is_search\": true, \"entity_label\": \"a zombie\", \"entity_id\": \"minecraft:zombie\"}\n\n");
        sb.append("If yes, identify the target creature and its Minecraft entity ID.\n");
        sb.append("Use the full Minecraft entity ID format: minecraft:<name>\n");
        sb.append("Examples: minecraft:chicken, minecraft:cow, minecraft:pig, minecraft:sheep,\n");
        sb.append("minecraft:horse, minecraft:zombie, minecraft:skeleton, minecraft:creeper,\n");
        sb.append("minecraft:spider, minecraft:enderman, minecraft:villager, minecraft:wolf,\n");
        sb.append("minecraft:cat, minecraft:fox, minecraft:rabbit, minecraft:bee, minecraft:bat,\n");
        sb.append("minecraft:squid, minecraft:dolphin, minecraft:cod, minecraft:salmon,\n");
        sb.append("minecraft:llama, minecraft:parrot, minecraft:ocelot, minecraft:panda,\n");
        sb.append("minecraft:polar_bear, minecraft:turtle, minecraft:witch, minecraft:pillager.\n\n");
        sb.append("If the player refers to 'it' or 'that one', resolve from the recent conversation above.\n\n");
        sb.append("Return ONLY this JSON with no extra text:\n");
        sb.append("{\"is_search\": true, \"entity_label\": \"a chicken\", \"entity_id\": \"minecraft:chicken\"}\n");
        sb.append("Or if not a search request:\n");
        sb.append("{\"is_search\": false}");
        return sb.toString();
    }

    public String scenarioIntentPrompt(String npcName, String playerName, String playerText) {
        String lower = playerText == null ? "" : playerText.toLowerCase(java.util.Locale.ROOT);
        boolean hasBuildVerb = lower.contains("build") || lower.contains("construct")
                || lower.contains("make a") || lower.contains("create a") || lower.contains("place a")
                || lower.contains("set up") || lower.contains("lay a") || lower.contains("put down");
        return "Classify this Minecraft player instruction.\n"
                + "Player (" + playerName + ") said to NPC (" + npcName + "): \"" + playerText + "\"\n"
                + "\n"
                + "RULE: classify as scenario_builder ONLY when the player asks the NPC to build, construct,\n"
                + "place, or create a physical structure or pattern of blocks in the world.\n"
                + "\n"
                + "scenario_builder examples:\n"
                + "  'build a 3x3 cobblestone floor' -> scenario_builder\n"
                + "  'build a small shelter here' -> scenario_builder\n"
                + "  'construct a wall to the north' -> scenario_builder\n"
                + "  'make a 3x3 platform out of oak planks' -> scenario_builder\n"
                + "  'place a cobblestone floor here' -> scenario_builder\n"
                + "  'build me a watchtower' -> scenario_builder\n"
                + "  'set up a small outpost' -> scenario_builder\n"
                + "\n"
                + "none examples (NOT scenario_builder):\n"
                + "  'go check out east' -> none\n"
                + "  'mine some cobblestone' -> none\n"
                + "  'fetch me some glass' -> none\n"
                + "  'do you have sticks' -> none\n"
                + "  'you there?' -> none\n"
                + "\n"
                + "The player said: \"" + playerText + "\"\n"
                + (hasBuildVerb
                        ? "The message contains a build/construct/place verb — this is likely scenario_builder.\n"
                        : "The message does NOT contain a build or construction verb — default to none.\n")
                + "\n"
                + "Return ONLY valid JSON, no extra text.\n"
                + "If this is NOT a build request (the default):\n"
                + "{\"intent\":\"none\",\"reasoning\":\"not a build request\",\"confidence\":0.9}\n"
                + "If this IS a build/construct/place request:\n"
                + "{\"intent\":\"scenario_builder\",\"reasoning\":\"player asked to build\",\"confidence\":0.9}";
    }

    public String scenarioPlanningPrompt(
            String npcName,
            String playerName,
            String instruction,
            WorldSnapshot snapshot) {
        String posStr = "unknown";
        if (snapshot != null && snapshot.npcPosition() != null) {
            WorldSnapshot.Position p = snapshot.npcPosition();
            posStr = "x=" + (int) p.x() + " y=" + (int) p.y() + " z=" + (int) p.z();
        }
        return "You are planning a multi-step task for a Minecraft NPC named " + npcName + ".\n"
                + "Player (" + playerName + ") requested: \"" + instruction + "\"\n"
                + "NPC current position: " + posStr + "\n"
                + "\n"
                + "Available step intents and their parameter formats:\n"
                + "  fetch_from_chest: {\"item_id\":\"minecraft:cobblestone\", \"count\":9}\n"
                + "  move_to:          {\"direction\":\"north|south|east|west\", \"distance\":10}\n"
                + "                 OR {\"x\":100, \"y\":64, \"z\":200}\n"
                + "  place_block:      {\"block\":\"minecraft:oak_planks\", \"relative_x\":0, \"relative_y\":0, \"relative_z\":0}\n"
                + "  place_pattern:    {\"block\":\"minecraft:cobblestone\", \"pattern\":\"floor|wall|pillar|outline\","
                + "                     \"width\":3, \"height\":3, \"length\":3,"
                + "                     \"relative_x\":0, \"relative_y\":0, \"relative_z\":0}\n"
                + "  mine_to_player:   {\"block\":\"minecraft:cobblestone\", \"count\":5}\n"
                + "  dialogue_reply:   {\"text\":\"I have completed the task.\"}\n"
                + "\n"
                + "PATTERN SELECTION — choose the correct pattern based on what the player asked:\n"
                + "  floor   = flat horizontal surface. Uses width x length blocks. height=1. Example: '3x3 floor', 'platform'.\n"
                + "  wall    = vertical standing surface. Uses width x height blocks. length is ignored. Example: '3x3 wall', '3 wide 3 tall wall'.\n"
                + "  pillar  = single vertical column. Uses only height. Example: 'pillar', 'column'.\n"
                + "  outline = hollow rectangle on the ground. Uses width x length. Example: 'outline', 'border'.\n"
                + "\n"
                + "CRITICAL RULES:\n"
                + "- ALWAYS use the exact block type the player named. NEVER substitute a different block.\n"
                + "  'stone wall' -> minecraft:stone. 'oak planks floor' -> minecraft:oak_planks. 'cobblestone floor' -> minecraft:cobblestone.\n"
                + "  minecraft:stone and minecraft:cobblestone are DIFFERENT blocks — never swap them.\n"
                + "- ALWAYS use the correct pattern for the structure type:\n"
                + "  'wall' in the request -> pattern must be 'wall'. 'floor' in the request -> pattern must be 'floor'.\n"
                + "- For a wall: width = horizontal extent, height = vertical extent. A '3x3 wall' means width=3, height=3.\n"
                + "- For a floor: width and length = footprint. A '3x3 floor' means width=3, length=3, height=1.\n"
                + "- relative_y=0 is correct for both floors and walls (built starting at the NPC's feet level).\n"
                + "- Plan exactly 3 steps: fetch_from_chest -> place_pattern -> dialogue_reply.\n"
                + "- Only add move_to if the player explicitly named a direction or location.\n"
                + "- Always end with a dialogue_reply step that reports what was built.\n"
                + "- Keep each description under 8 words.\n"
                + "- Supported block ids: oak_planks, cobblestone, stone, dirt, glass, oak_log, sand, bricks, stone_bricks.\n"
                + "\n"
                + "EXAMPLE 1 — 'build a 3x3 cobblestone floor':\n"
                + "{\"scenario\":\"cobblestone floor\","
                + "\"announce\":\"I will fetch cobblestone and lay a 3x3 floor here.\","
                + "\"steps\":["
                + "{\"intent\":\"fetch_from_chest\",\"parameters\":{\"item_id\":\"minecraft:cobblestone\",\"count\":9},"
                + "\"description\":\"Fetch 9 cobblestone from chest.\"},"
                + "{\"intent\":\"place_pattern\",\"parameters\":{\"block\":\"minecraft:cobblestone\",\"pattern\":\"floor\","
                + "\"width\":3,\"height\":1,\"length\":3,\"relative_x\":0,\"relative_y\":0,\"relative_z\":0},"
                + "\"description\":\"Place 3x3 cobblestone floor.\"},"
                + "{\"intent\":\"dialogue_reply\",\"parameters\":{\"text\":\"Done! I laid a 3x3 cobblestone floor.\"},"
                + "\"description\":\"Report completion.\"}]}\n"
                + "\n"
                + "EXAMPLE 2 — 'build a 3x3 stone wall':\n"
                + "{\"scenario\":\"stone wall\","
                + "\"announce\":\"I will build a 3x3 stone wall.\","
                + "\"steps\":["
                + "{\"intent\":\"fetch_from_chest\",\"parameters\":{\"item_id\":\"minecraft:stone\",\"count\":9},"
                + "\"description\":\"Fetch 9 stone from chest.\"},"
                + "{\"intent\":\"place_pattern\",\"parameters\":{\"block\":\"minecraft:stone\",\"pattern\":\"wall\","
                + "\"width\":3,\"height\":3,\"length\":1,\"relative_x\":0,\"relative_y\":0,\"relative_z\":0},"
                + "\"description\":\"Place 3x3 stone wall.\"},"
                + "{\"intent\":\"dialogue_reply\",\"parameters\":{\"text\":\"Done! I built a 3x3 stone wall.\"},"
                + "\"description\":\"Report completion.\"}]}\n"
                + "\n"
                + "Now plan the task the player requested. Return ONLY this JSON with no extra text:\n"
                + "{\"scenario\":\"short task name\","
                + "\"announce\":\"one sentence plan\","
                + "\"steps\":["
                + "{\"intent\":\"step_intent\",\"parameters\":{},\"description\":\"short description\"}]}";
    }

    private JsonObject memoryToJson(MemoryContext memory) {
        JsonObject json = new JsonObject();
        json.add("short_term", toArray(memory.shortTerm()));
        json.add("working", toArray(memory.working()));
        json.add("long_term", toArray(memory.longTerm()));
        return json;
    }

    public String tradeOutOfStockPrompt(String npcName, String playerName, String itemId, int stock) {
        JsonObject payload = new JsonObject();
        payload.addProperty("task", "trade_out_of_stock");
        payload.addProperty("npc_name", npcName);
        payload.addProperty("player_name", playerName);
        payload.addProperty("item_id", itemId);
        payload.addProperty("stock", stock);
        payload.addProperty("format", "{\"response_text\":\"...\"}");
        payload.addProperty("constraints",
                "Return strict JSON only. Write one short in-character sentence (max 16 words) where the NPC "
                + "explains they don't have enough of the requested item in stock. "
                + "If stock is 0, say they have none. If stock > 0 but less than requested, mention the actual amount. "
                + "Use first-person speech. No system tags, no narration.");
        return payload.toString();
    }

    public String mineTaskStartPrompt(String npcName, String playerName, int count, String blockId) {
        JsonObject payload = new JsonObject();
        payload.addProperty("task", "mine_task_start");
        payload.addProperty("npc_name", npcName);
        payload.addProperty("player_name", playerName);
        payload.addProperty("count", count);
        payload.addProperty("block_id", blockId);
        payload.addProperty("format", "{\"response_text\":\"...\"}");
        payload.addProperty("constraints",
                "Return strict JSON only. Write one short in-character sentence (max 14 words) where the NPC "
                + "confirms they are heading out to mine the requested blocks. "
                + "Use first-person speech. No system tags, no narration, no follow-up questions.");
        return payload.toString();
    }

    public String mineTaskCompletePrompt(String npcName, String playerName, int count, String blockId) {
        JsonObject payload = new JsonObject();
        payload.addProperty("task", "mine_task_complete");
        payload.addProperty("npc_name", npcName);
        payload.addProperty("player_name", playerName);
        payload.addProperty("count", count);
        payload.addProperty("block_id", blockId);
        payload.addProperty("format", "{\"response_text\":\"...\"}");
        payload.addProperty("constraints",
                "Return strict JSON only. Write one short in-character sentence (max 14 words) where the NPC "
                + "announces they have delivered the mined blocks to the player's inventory. "
                + "Use first-person speech. No system tags, no narration, no follow-up questions.");
        return payload.toString();
    }

    private JsonArray toArray(Iterable<MemoryEntry> entries) {
        JsonArray array = new JsonArray();
        for (MemoryEntry entry : entries) {
            array.add(entry.toJson());
        }
        return array;
    }
}
