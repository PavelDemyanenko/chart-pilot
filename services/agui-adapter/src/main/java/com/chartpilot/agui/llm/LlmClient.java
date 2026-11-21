package com.chartpilot.agui.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.chartpilot.agui.config.AdapterProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * OpenAI-совместимый chat/completions (stream=false) напрямую в endpoint коннектора.
 */
@Component
public class LlmClient {

    private final ConnectorConfigProvider configProvider;
    private final ObjectMapper mapper;
    private final AdapterProperties properties;
    private final HttpClient http;

    public LlmClient(ConnectorConfigProvider configProvider, ObjectMapper mapper, AdapterProperties properties) {
        this.configProvider = configProvider;
        this.mapper = mapper;
        this.properties = properties;
        // Внешние LLM-endpoint'ы могут быть доступны только через сетевой прокси
        // (прямой egress из кластера блокируется DPI); внутренние вызовы OS — без прокси.
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5));
        if (properties.hasProxy()) {
            builder.proxy(java.net.ProxySelector.of(
                    new java.net.InetSocketAddress(properties.proxyHost(), properties.proxyPort())));
        }
        this.http = builder.build();
    }

    public JsonNode chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools) throws Exception {
        ConnectorConfigProvider.LlmConfig config = configProvider.get();
        Map<String, Object> payload = new HashMap<>();
        payload.put("model", config.model());
        payload.put("messages", messages);
        payload.put("tools", tools);
        payload.put("stream", false);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(config.endpoint()))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)));
        // ML Commons маскирует credential в GET коннектора — приоритет у env-ключа
        String apiKey = !properties.apiKey().isBlank() ? properties.apiKey() : config.apiKey();
        if (!apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            configProvider.invalidate();
            throw new IllegalStateException("LLM endpoint returned HTTP " + response.statusCode()
                    + ": " + response.body());
        }
        return mapper.readTree(response.body());
    }
}