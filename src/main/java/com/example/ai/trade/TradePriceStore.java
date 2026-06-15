package com.example.ai.trade;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TradePriceStore {
    private static final Path CONFIG_PATH =
            Path.of("config", "llm_npc", "trade_prices.json");

    public static Map<String, PriceConfig> load() {
        Map<String, PriceConfig> result = new LinkedHashMap<>();
        if (!Files.exists(CONFIG_PATH)) return result;
        try (Reader r = Files.newBufferedReader(CONFIG_PATH)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            for (var entry : root.entrySet()) {
                try {
                    result.put(entry.getKey(), parseConfig(entry.getValue()));
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return result;
    }

    public static void save(Map<String, PriceConfig> configs) throws IOException {
        Files.createDirectories(CONFIG_PATH.getParent());
        JsonObject root = new JsonObject();
        for (var entry : configs.entrySet()) {
            PriceConfig cfg = entry.getValue();
            JsonObject item = new JsonObject();
            item.addProperty("base", cfg.base());
            item.addProperty("max", cfg.max());
            item.addProperty("discount", cfg.discountPct());
            root.add(entry.getKey(), item);
        }
        try (Writer w = Files.newBufferedWriter(CONFIG_PATH)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(root, w);
        }
    }

    private static PriceConfig parseConfig(JsonElement el) {
        if (el.isJsonPrimitive()) {
            return PriceConfig.defaults(el.getAsInt());
        }
        JsonObject obj = el.getAsJsonObject();
        int base = obj.has("base") ? obj.get("base").getAsInt() : 2;
        int max  = obj.has("max")  ? obj.get("max").getAsInt()  : base * 2;
        int disc = obj.has("discount") ? obj.get("discount").getAsInt() : 15;
        return new PriceConfig(base, max, disc);
    }

    public static String toJson(Map<String, PriceConfig> configs) {
        JsonObject root = new JsonObject();
        for (var entry : configs.entrySet()) {
            PriceConfig cfg = entry.getValue();
            JsonObject item = new JsonObject();
            item.addProperty("base", cfg.base());
            item.addProperty("max", cfg.max());
            item.addProperty("discount", cfg.discountPct());
            root.add(entry.getKey(), item);
        }
        return root.toString();
    }

    public static Map<String, PriceConfig> fromJson(String json) {
        Map<String, PriceConfig> result = new LinkedHashMap<>();
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            for (var entry : root.entrySet()) {
                try {
                    result.put(entry.getKey(), parseConfig(entry.getValue()));
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return result;
    }
}
