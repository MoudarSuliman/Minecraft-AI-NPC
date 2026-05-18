package com.example.ai.trade;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TradeIntentParser {
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\b(\\d+)\\b");

    private static final Map<String, String> ITEM_ALIASES = Map.ofEntries(
            Map.entry("grass block", "minecraft:grass_block"),
            Map.entry("grass", "minecraft:grass_block"),
            Map.entry("glass block", "minecraft:glass"),
            Map.entry("glass", "minecraft:glass"),
            Map.entry("cobblestone", "minecraft:cobblestone"),
            Map.entry("oak log", "minecraft:oak_log"),
            Map.entry("log", "minecraft:oak_log"),
            Map.entry("oak planks", "minecraft:oak_planks"),
            Map.entry("planks", "minecraft:oak_planks"),
            Map.entry("stone", "minecraft:stone"),
            Map.entry("dirt", "minecraft:dirt"),
            Map.entry("sand", "minecraft:sand"),
            Map.entry("stick", "minecraft:stick"),
            Map.entry("sticks", "minecraft:stick")
    );

    public ParsedTradeIntent parse(String text) {
        if (text == null || text.isBlank()) {
            return ParsedTradeIntent.none();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        String trimmed = lower.trim();

        if (containsAny(lower, "what do you sell", "what do you have", "show stock", "show me your stock", "what items")) {
            return new ParsedTradeIntent(TradeIntentType.INQUIRE_STOCK, "", 0, null);
        }
        if (containsAny(lower, "do you have", "have you got", "got any")) {
            String itemId = detectItem(lower);
            if (!itemId.isEmpty()) {
                return new ParsedTradeIntent(TradeIntentType.INQUIRE_STOCK, itemId, 1, null);
            }
        }
        if (containsAny(lower, "are we done trading", "done trading", "still trading", "trade done", "trading or no")) {
            return new ParsedTradeIntent(TradeIntentType.INQUIRE_SESSION_STATUS, "", 0, null);
        }
        if (containsAny(lower, "how much", "what price", "what's the price", "how much for", "what do you charge", "cost", "price")) {
            String itemId = detectItem(lower);
            if (!itemId.isEmpty()) {
                int count = firstNumber(lower) == null ? 1 : Math.max(1, firstNumber(lower));
                return new ParsedTradeIntent(TradeIntentType.REQUEST_OFFER, itemId, count, null);
            }
            // If no specific item, treat as generic stock inquiry (they want to know prices)
            return new ParsedTradeIntent(TradeIntentType.INQUIRE_STOCK, "", 0, null);
        }
        if (containsAny(lower, "do you accept", "would you accept", "will you accept")
                && containsAny(lower, "emerald", "emeralds")
                && lower.contains("for")) {
            String itemId = detectItem(lower);
            if (!itemId.isEmpty()) {
                Integer detected = firstNumber(lower);
                int quantity = detected == null ? 1 : Math.max(1, detected);
                return new ParsedTradeIntent(TradeIntentType.REQUEST_OFFER, itemId, quantity, null);
            }
            Integer proposed = firstNumber(lower);
            if (proposed != null) {
                return new ParsedTradeIntent(TradeIntentType.COUNTER_OFFER, "", 0, proposed);
            }
        }
        if (isPaymentInquiry(lower)) {
            return new ParsedTradeIntent(TradeIntentType.INQUIRE_PAYMENT, "", 0, null);
        }
        if (isAcceptancePhrase(lower, trimmed)) {
            return new ParsedTradeIntent(TradeIntentType.ACCEPT_OFFER, "", 0, null);
        }
        if (containsAny(lower, "too expensive", "expensive", "too much", "cheaper", "lower", "discount")) {
            return new ParsedTradeIntent(TradeIntentType.COUNTER_OFFER, "", 0, null);
        }
        if (containsAny(lower, "i dont like it", "i don't like it", "not good", "dont like this", "don't like this")) {
            return new ParsedTradeIntent(TradeIntentType.COUNTER_OFFER, "", 0, null);
        }
        if (containsAny(lower, "can you do", "could you do", "how about", "what about", "make it")) {
            Integer proposed = firstNumber(lower);
            return new ParsedTradeIntent(TradeIntentType.COUNTER_OFFER, "", 0, proposed);
        }
        if (isDirectPaymentOffer(lower)) {
            String itemId = detectItem(lower);
            if (!itemId.isEmpty()) {
                int count = firstNumber(lower) == null ? 1 : Math.max(1, firstNumber(lower));
                return new ParsedTradeIntent(TradeIntentType.REQUEST_OFFER, itemId, count, null);
            }
        }
        if (isRefusalPhrase(trimmed)) {
            return new ParsedTradeIntent(TradeIntentType.DECLINE_OFFER, "", 0, null);
        }
        if (containsAny(lower, "cancel", "decline", "never mind", "no thanks", "not now")) {
            return new ParsedTradeIntent(TradeIntentType.DECLINE_OFFER, "", 0, null);
        }
        if (containsAny(lower, "for") && containsAny(lower, "emerald", "emeralds")) {
            Integer proposed = emeraldAmount(lower);
            if (proposed != null) {
                return new ParsedTradeIntent(TradeIntentType.COUNTER_OFFER, "", 0, proposed);
            }
        }
        if (containsAny(lower, "buy", "trade", "want", "need", "can i get", "give me")) {
            String itemId = detectItem(lower);
            if (itemId.isEmpty()) {
                return ParsedTradeIntent.none();
            }
            int count = firstNumber(lower) == null ? 1 : Math.max(1, firstNumber(lower));
            return new ParsedTradeIntent(TradeIntentType.REQUEST_OFFER, itemId, count, null);
        }

        return ParsedTradeIntent.none();
    }

    private boolean isAcceptancePhrase(String lower, String trimmed) {
        if (lower.contains("do you accept")) {
            return false;
        }
        return containsAny(lower, "deal", "i accept", "i'll take it", "ill take it", "sounds good")
                || lower.contains("let's do that")
                || lower.contains("lets do that")
                || lower.contains("go ahead")
                || trimmed.equals("sure")
                || trimmed.equals("yeah")
                || trimmed.equals("yep")
                || trimmed.equals("yes")
                || trimmed.equals("ok")
                || trimmed.equals("okay");
    }

    private boolean isPaymentInquiry(String lower) {
        if (containsAny(lower, "instead of emerald", "instead of emeralds")) {
            return true;
        }
        if (containsAny(lower, "pay with", "trade for")) {
            return !containsAny(lower, "emerald", "emeralds");
        }
        if (containsAny(lower, "do you accept", "would you accept", "will you accept")) {
            return !containsAny(lower, "emerald", "emeralds");
        }
        return false;
    }

    private boolean isRefusalPhrase(String trimmed) {
        return trimmed.equals("no")
                || trimmed.equals("nope")
                || trimmed.equals("nah");
    }

    private boolean isDirectPaymentOffer(String lower) {
        return containsAny(lower, "i'll give", "ill give", "i can give", "i will give", "pay", "for")
                && containsAny(lower, "emerald", "emeralds");
    }

    private String detectItem(String lower) {
        for (Map.Entry<String, String> entry : ITEM_ALIASES.entrySet()) {
            if (lower.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return "";
    }

    private Integer firstNumber(String lower) {
        Matcher matcher = NUMBER_PATTERN.matcher(lower);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Integer numberAfterFor(String lower) {
        Matcher matcher = Pattern.compile("\\bfor\\s+(\\d+)\\b").matcher(lower);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Integer emeraldAmount(String lower) {
        Matcher matcher = Pattern.compile("\\b(\\d+)\\s+emeralds?\\b").matcher(lower);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean containsAny(String value, String... probes) {
        for (String probe : probes) {
            if (value.contains(probe)) {
                return true;
            }
        }
        return false;
    }
}
