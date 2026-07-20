package com.example.ai.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LlmRouter {
    public static final String UNAVAILABLE_SENTINEL = "llm unavailable";

    private static final Logger LOG = LoggerFactory.getLogger("llm_npc");

    private final LlmClient primary;
    private final LlmClient escalated;

    public LlmRouter(LlmClient primary, LlmClient escalated) {
        this.primary = primary;
        this.escalated = escalated;
    }

    public static LlmRouter defaultRouter() {
        LlmConfig cfg = LlmConfig.load();
        LlmClient ollama = new OllamaLlmClient("http://127.0.0.1:11434/api/generate", cfg.localModel);
        LlmClient cloud  = buildCloudClient(cfg, ollama);

        String cloudDesc = (cfg.cloudApiKey != null && !cfg.cloudApiKey.isBlank())
                ? cfg.cloudProvider + "/" + cfg.cloudModel
                : "none (no API key)";
        LoggerFactory.getLogger("llm_npc").info("[LLM] mode={} cloud={}", cfg.mode, cloudDesc);

        return switch (cfg.mode) {
            case local -> new LlmRouter(ollama, ollama);
            case cloud -> new LlmRouter(cloud,  cloud);
            case auto  -> new LlmRouter(ollama, cloud);
        };
    }

    private static LlmClient buildCloudClient(LlmConfig cfg, LlmClient fallback) {
        if (cfg.cloudApiKey == null || cfg.cloudApiKey.isBlank()) return fallback;
        return switch (cfg.cloudProvider) {
            case openai    -> new Gpt4oLlmClient(cfg.cloudApiKey, cfg.cloudModel);
            case anthropic -> new AnthropicLlmClient(cfg.cloudApiKey, cfg.cloudModel);
            case gemini    -> new GeminiLlmClient(cfg.cloudApiKey, cfg.cloudModel);
        };
    }

    public String generate(String prompt) {
        long startMillis = System.currentTimeMillis();
        try {
            String response = shouldEscalate(prompt) ? escalated.generate(prompt) : primary.generate(prompt);
            logTraffic(prompt, response, startMillis, null);
            return response;
        } catch (Exception primaryError) {
            try {
                String response = escalated.generate(prompt);
                logTraffic(prompt, response, startMillis, "recovered-on-fallback");
                return response;
            } catch (Exception fallbackError) {
                LOG.warn("[LLM] request failed after {} ms: {}",
                        System.currentTimeMillis() - startMillis, fallbackError.getMessage());
                return "{\"intent\":\"idle\",\"parameters\":{},\"reasoning\":\"" + UNAVAILABLE_SENTINEL + "\",\"priority\":0.05}";
            }
        }
    }

    private void logTraffic(String prompt, String response, long startMillis, String note) {
        LOG.info("[LLM] sent={} chars, received={} chars, latency={} ms{}",
                prompt == null ? 0 : prompt.length(),
                response == null ? 0 : response.length(),
                System.currentTimeMillis() - startMillis,
                note == null ? "" : " (" + note + ")");
    }

    private boolean shouldEscalate(String prompt) {
        String lower = prompt.toLowerCase();
        return lower.contains("\"intent\":\"build_structure\"")
                || lower.contains("multi-step")
                || lower.length() > 3500;
    }
}
