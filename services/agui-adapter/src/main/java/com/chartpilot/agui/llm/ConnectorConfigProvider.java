package com.chartpilot.agui.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import com.chartpilot.agui.config.AdapterProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Читает конфигурацию LLM-коннектора из OpenSearch (endpoint, model, credential)
 * и кэширует её. Креденшалы не хранятся в адаптере отдельно от OpenSearch.
 */
@Component
public class ConnectorConfigProvider {

    public record LlmConfig(String endpoint, String model, String apiKey) {
    }

    private static final Logger log = LoggerFactory.getLogger(ConnectorConfigProvider.class);

    private final AdapterProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private volatile LlmConfig cached;

    public ConnectorConfigProvider(AdapterProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    public LlmConfig get() throws Exception {
        LlmConfig current = cached;
        if (current != null) {
            return current;
        }
        String body = mapper.writeValueAsString(Map.of(
                "query", Map.of("match", Map.of("name", properties.connectorName())),
                "size", 1));
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(properties.opensearchUrl() + "/_plugins/_ml/connectors/_search"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Connector search failed: HTTP " + response.statusCode());
        }
        JsonNode source = mapper.readTree(response.body())
                .path("hits").path("hits").path(0).path("_source");
        if (source.isMissingNode()) {
            throw new IllegalStateException("Connector not found: " + properties.connectorName());
        }
        LlmConfig config = new LlmConfig(
                source.path("parameters").path("endpoint").asText(),
                source.path("parameters").path("model").asText(),
                source.path("credential").path("openAI_key").asText(""));
        log.info("Loaded LLM connector config: endpoint={}, model={}", config.endpoint(), config.model());
        cached = config;
        return config;
    }

    public void invalidate() {
        cached = null;
    }
}