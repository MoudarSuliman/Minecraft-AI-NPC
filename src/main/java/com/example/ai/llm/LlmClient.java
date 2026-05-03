package com.example.ai.llm;

public interface LlmClient {
    String generate(String prompt) throws Exception;
}
