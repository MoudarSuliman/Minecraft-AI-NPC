package com.example.ai.llm;

import org.slf4j.LoggerFactory;

public final class LlmRouter {
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
        try {
            if (shouldEscalate(prompt)) {
                return escalated.generate(prompt);
            }
            return primary.generate(prompt);
        } catch (Exception primaryError) {
            try {
                return escalated.generate(prompt);
            } catch (Exception fallbackError) {
                return "{\"intent\":\"idle\",\"parameters\":{},\"reasoning\":\"llm unavailable\",\"priority\":0.05}";
            }
        }
    }

    private boolean shouldEscalate(String prompt) {
        String lower = prompt.toLowerCase();
        return lower.contains("\"intent\":\"build_structure\"")
                || lower.contains("multi-step")
                || lower.length() > 3500;
    }
}
