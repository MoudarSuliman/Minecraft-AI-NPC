package com.example.ai.llm;

public final class LlmRouter {
    private final LlmClient primary;
    private final LlmClient escalated;

    public LlmRouter(LlmClient primary, LlmClient escalated) {
        this.primary = primary;
        this.escalated = escalated;
    }

    public static LlmRouter defaultRouter() {
        String apiKey = System.getenv("OPENAI_API_KEY");
        LlmClient ollama = new OllamaLlmClient("http://127.0.0.1:11434/api/generate", "llama3");
        LlmClient gpt = (apiKey == null || apiKey.isBlank())
                ? ollama
                : new Gpt4oLlmClient(apiKey);
        return new LlmRouter(ollama, gpt);
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
