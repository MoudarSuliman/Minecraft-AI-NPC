package com.example.ai.trade;

import java.util.Map;
import java.util.Set;

public final class TradeOfferEngine {
    private static final int MAX_STOCK_REFERENCE = 64;
    private static final int MAX_DISCOUNT_PCT = 15;
    private static final long OFFER_EXPIRY_MILLIS = 120_000L;

    private final Map<String, Integer> basePricesByItem;
    private final Map<String, Integer> maxUnitPriceByItem;

    public TradeOfferEngine(Map<String, Integer> basePricesByItem, Map<String, Integer> maxUnitPriceByItem) {
        this.basePricesByItem = basePricesByItem;
        this.maxUnitPriceByItem = maxUnitPriceByItem;
    }

    public static TradeOfferEngine defaultEngine() {
        return new TradeOfferEngine(Map.of(
                "minecraft:grass_block", 1,
                "minecraft:glass", 2,
                "minecraft:cobblestone", 1,
                "minecraft:oak_log", 2,
                "minecraft:oak_planks", 1,
                "minecraft:stone", 1,
                "minecraft:dirt", 1,
                "minecraft:sand", 1
        ), Map.of(
                "minecraft:grass_block", 2,
                "minecraft:cobblestone", 2,
                "minecraft:dirt", 2,
                "minecraft:sand", 2,
                "minecraft:stone", 2,
                "minecraft:oak_planks", 2,
                "minecraft:glass", 3,
                "minecraft:oak_log", 3
        ));
    }

    public TradeOffer quote(
            String itemId,
            int quantity,
            int stock,
            int playerReputation,
            boolean urgentDemand,
            long nowMillis
    ) {
        return quote(itemId, quantity, stock, playerReputation, urgentDemand, nowMillis, null, null);
    }

    public TradeOffer quote(
            String itemId,
            int quantity,
            int stock,
            int playerReputation,
            boolean urgentDemand,
            long nowMillis,
            Integer suggestedUnitPrice,
            Integer suggestedTotalPrice
    ) {
        int normalizedQuantity = Math.max(1, quantity);
        int base = basePricesByItem.getOrDefault(itemId, 2);

        double scarcityMultiplier = 1.0 + (1.0 - Math.min(stock, MAX_STOCK_REFERENCE) / (double) MAX_STOCK_REFERENCE) * 0.35;
        double reputationMultiplier = 1.1 - ((Math.max(-100, Math.min(100, playerReputation)) + 100) / 200.0) * 0.2;
        double demandMultiplier = urgentDemand ? 1.1 : 1.0;

        int computedUnitPrice = (int) Math.round(base * scarcityMultiplier * reputationMultiplier * demandMultiplier);
        int itemCap = maxUnitPriceByItem.getOrDefault(itemId, base * 3);
        int boundedUnitPrice = clampPrice(itemCap, suggestedUnitPrice, computedUnitPrice);
        if (suggestedTotalPrice != null && suggestedTotalPrice > 0) {
            int totalCap = itemCap * normalizedQuantity;
            int boundedTotal = Math.max(normalizedQuantity, Math.min(totalCap, suggestedTotalPrice));
            boundedUnitPrice = Math.max(1, boundedTotal / normalizedQuantity);
        }
        int totalPrice = boundedUnitPrice * normalizedQuantity;
        long expiresAt = nowMillis + OFFER_EXPIRY_MILLIS;

        return new TradeOffer(itemId, normalizedQuantity, boundedUnitPrice, totalPrice, MAX_DISCOUNT_PCT, expiresAt);
    }

    private int clampPrice(int itemCap, Integer suggestedPrice, int computedPrice) {
        int candidate = suggestedPrice != null && suggestedPrice > 0 ? suggestedPrice : computedPrice;
        return Math.max(1, Math.min(itemCap, candidate));
    }

    public int minimumAcceptableTotal(TradeOffer offer) {
        return (int) Math.ceil(offer.totalPrice() * (1.0 - offer.maxDiscountPct() / 100.0));
    }

    public TradeCounterResult evaluateCounter(TradeOffer currentOffer, int proposedTotalPrice, long nowMillis) {
        int minimumAcceptableTotal = minimumAcceptableTotal(currentOffer);
        if (proposedTotalPrice < minimumAcceptableTotal) {
            return new TradeCounterResult(false, minimumAcceptableTotal, currentOffer);
        }

        int safeTotal = Math.max(1, proposedTotalPrice);
        int unitPrice = Math.max(1, safeTotal / Math.max(1, currentOffer.quantity()));
        TradeOffer acceptedOffer = new TradeOffer(
                currentOffer.itemId(),
                currentOffer.quantity(),
                unitPrice,
                safeTotal,
                currentOffer.maxDiscountPct(),
                nowMillis + OFFER_EXPIRY_MILLIS
        );
        return new TradeCounterResult(true, minimumAcceptableTotal, acceptedOffer);
    }

    public boolean supportsItem(String itemId) {
        return basePricesByItem.containsKey(itemId);
    }

    public Set<String> supportedItems() {
        return basePricesByItem.keySet();
    }
}
