package com.example.ai.llm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

public final class LlmConfig {

    public enum ModelMode     { local, cloud, auto }
    public enum CloudProvider { openai, anthropic, gemini }

    public final ModelMode     mode;
    public final CloudProvider cloudProvider;
    public final String        cloudModel;
    public final String        cloudApiKey;

    private static final Path CONFIG_PATH = Path.of("config", "llm_npc", "config.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private LlmConfig(ModelMode mode, CloudProvider cloudProvider,
                      String cloudModel, String cloudApiKey) {
        this.mode          = mode;
        this.cloudProvider = cloudProvider;
        this.cloudModel    = cloudModel;
        this.cloudApiKey   = cloudApiKey;
    }

    public static LlmConfig load() {
        try {
            if (!Files.exists(CONFIG_PATH)) {
                writeDefaults();
            }
            JsonObject json = JsonParser.parseString(Files.readString(CONFIG_PATH)).getAsJsonObject();

            ModelMode mode = parseEnum(ModelMode.class,
                    getString(json, "model", "auto"), ModelMode.auto);

            CloudProvider provider = parseEnum(CloudProvider.class,
                    getString(json, "cloud_provider", "openai"), CloudProvider.openai);

            String defaultModel = switch (provider) {
                case openai    -> "gpt-4o";
                case anthropic -> "claude-sonnet-4-6";
                case gemini    -> "gemini-2.0-flash";
            };
            String cloudModel = getString(json, "cloud_model", defaultModel);

            String key = getString(json, "cloud_api_key", "");
            if (key.isBlank()) {
                String env = System.getenv("CLOUD_API_KEY");
                if (env != null) key = env;
            }

            return new LlmConfig(mode, provider, cloudModel, key);
        } catch (Exception e) {
            LoggerFactory.getLogger("llm_npc").warn("Failed to load LLM config, using defaults", e);
            return new LlmConfig(ModelMode.auto, CloudProvider.openai, "gpt-4o", "");
        }
    }

    private static void writeDefaults() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject json = new JsonObject();
            json.addProperty("model",          "auto");
            json.addProperty("cloud_provider", "openai");
            json.addProperty("cloud_model",    "gpt-4o");
            json.addProperty("cloud_api_key",  "");
            Files.writeString(CONFIG_PATH, GSON.toJson(json));
        } catch (Exception e) {
            LoggerFactory.getLogger("llm_npc").warn("Failed to write default LLM config", e);
        }
    }

    private static String getString(JsonObject json, String key, String fallback) {
        return json.has(key) ? json.get(key).getAsString() : fallback;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> cls, String value, E fallback) {
        try {
            return Enum.valueOf(cls, value.toLowerCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
