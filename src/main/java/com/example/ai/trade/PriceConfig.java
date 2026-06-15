package com.example.ai.trade;

public record PriceConfig(int base, int max, int discountPct) {
    public PriceConfig {
        base = Math.max(1, base);
        max = Math.max(base, max);
        discountPct = Math.max(0, Math.min(50, discountPct));
    }

    public static PriceConfig defaults(int base) {
        return new PriceConfig(base, base * 2, 15);
    }
}
