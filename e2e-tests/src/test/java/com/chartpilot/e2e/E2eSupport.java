package com.chartpilot.e2e;

import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;

public final class E2eSupport {

    public static final String BRIDGE_URL = System.getProperty("bridgeUrl", "http://127.0.0.1:30880");
    public static final String OSD_URL = System.getProperty("osdUrl", "http://127.0.0.1:30560");
    public static final String OPENSEARCH_URL = System.getProperty("opensearchUrl", "http://127.0.0.1:30920");
    public static final String PG_URL = System.getProperty("pgUrl", "jdbc:postgresql://127.0.0.1:30432/chartpilot");
    public static final String PG_USER = System.getProperty("pgUser", "chartpilot");
    public static final String PG_PASSWORD = System.getProperty("pgPassword", "chartpilot");

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private E2eSupport() {
    }

    public static McpSyncClient connectMcp() {
        HttpClientSseClientTransport transport = HttpClientSseClientTransport.builder(BRIDGE_URL).build();
        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(90))
                .build();
        client.initialize();
        return client;
    }

    public static JsonNode callTool(McpSyncClient client, String name, Map<String, Object> args) throws Exception {
        McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(name, args));
        StringBuilder text = new StringBuilder();
        for (McpSchema.Content content : result.content()) {
            if (content instanceof McpSchema.TextContent textContent) {
                text.append(textContent.text());
            }
        }
        return MAPPER.readTree(text.toString());
    }
}