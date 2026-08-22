package com.example.ai.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class OllamaLlmClient implements LlmClient {
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final URI endpoint;
    private final String model;

    public OllamaLlmClient(String endpoint, String model) {
        this.endpoint = URI.create(endpoint);
        this.model = model;
    }

    @Override
    public String generate(String prompt) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("model", model);
        payload.addProperty("prompt", prompt);
        payload.addProperty("stream", false);
        payload.addProperty("format", "json");
        payload.addProperty("keep_alive", "30m");

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        if (root.has("error") && !root.get("error").isJsonNull()) {
            throw new IllegalStateException("Ollama error for model '" + model + "': " + root.get("error").getAsString());
        }
        if (!root.has("response") || root.get("response").isJsonNull()) {
            throw new IllegalStateException("Ollama returned no 'response' field for model '" + model
                    + "' (HTTP " + response.statusCode() + "). Check that the model tag matches 'ollama list'.");
        }
        return root.get("response").getAsString();
    }
}
