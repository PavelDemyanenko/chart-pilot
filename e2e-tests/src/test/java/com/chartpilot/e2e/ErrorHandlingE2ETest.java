package com.chartpilot.e2e;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E-сценарий 3 (acceptance scenarios): requestChart с несуществующим sessionId возвращает
 * структурированную ошибку и не роняет MCP-сервер.
 */
class ErrorHandlingE2ETest {

    @Test
    void requestChartWithInvalidSessionReturnsStructuredErrorAndServerStaysAlive() throws Exception {
        try (McpSyncClient client = E2eSupport.connectMcp()) {
            JsonNode error = E2eSupport.callTool(client, "requestChart",
                    Map.of("sessionId", "no-such-session", "prompt", "построй график"));

            assertThat(error.path("status").asText()).isEqualTo("error");
            assertThat(error.path("code").asText()).isEqualTo("NOT_FOUND");
            assertThat(error.path("message").asText()).contains("createSession");

            JsonNode created = E2eSupport.callTool(client, "createSession", Map.of());
            assertThat(created.path("sessionId").asText()).isNotBlank();
            assertThat(created.path("status").asText()).isEqualTo("WAITING_FOR_BROWSER");

            JsonNode status = E2eSupport.callTool(client, "getSessionStatus",
                    Map.of("sessionId", "no-such-session"));
            assertThat(status.path("status").asText()).isEqualTo("NOT_FOUND");
        }
    }
}