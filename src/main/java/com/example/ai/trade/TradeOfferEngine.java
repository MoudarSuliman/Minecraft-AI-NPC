package com.example.ai.trade;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class TradeOfferEngine {
    private static final int MAX_STOCK_REFERENCE = 64;
    private static final long OFFER_EXPIRY_MILLIS = 120_000L;

    private final Map<String, PriceConfig> configs = new ConcurrentHashMap<>();

    public TradeOfferEngine(Map<String, PriceConfig> initialConfigs) {
        configs.putAll(initialConfigs);
    }

    public void updatePrices(Map<String, PriceConfig> newConfigs) {
        configs.clear();
        configs.putAll(newConfigs);
    }

    public Map<String, PriceConfig> currentConfigs() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(configs));
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
        PriceConfig cfg = configs.getOrDefault(itemId, PriceConfig.defaults(2));
        int base = cfg.base();
        int itemCap = cfg.max();
        int discountPct = cfg.discountPct();

        double scarcityMultiplier = 1.0 + (1.0 - Math.min(stock, MAX_STOCK_REFERENCE) / (double) MAX_STOCK_REFERENCE) * 0.35;
        double reputationMultiplier = 1.1 - ((Math.max(-100, Math.min(100, playerReputation)) + 100) / 200.0) * 0.2;
        double demandMultiplier = urgentDemand ? 1.1 : 1.0;

        int computedUnitPrice = (int) Math.round(base * scarcityMultiplier * reputationMultiplier * demandMultiplier);
        int boundedUnitPrice = clampPrice(itemCap, suggestedUnitPrice, computedUnitPrice);
        if (suggestedTotalPrice != null && suggestedTotalPrice > 0) {
            int totalCap = itemCap * normalizedQuantity;
            int boundedTotal = Math.max(normalizedQuantity, Math.min(totalCap, suggestedTotalPrice));
            boundedUnitPrice = Math.max(1, boundedTotal / normalizedQuantity);
        }
        int totalPrice = boundedUnitPrice * normalizedQuantity;
        long expiresAt = nowMillis + OFFER_EXPIRY_MILLIS;

        return new TradeOffer(itemId, normalizedQuantity, boundedUnitPrice, totalPrice, discountPct, expiresAt);
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
        return itemId != null && !itemId.isBlank();
    }

    public Set<String> supportedItems() {
        return configs.keySet();
    }
}
