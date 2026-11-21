package com.chartpilot.bridge;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.chartpilot.bridge.tools.DashboardTools;
import com.chartpilot.bridge.tools.ToolResponse.CommandOutcome;
import com.chartpilot.bridge.tools.ToolResponse.SessionCreated;
import com.chartpilot.bridge.tools.ToolResponse.SessionStatusView;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.shutdown=immediate")
class BridgeIntegrationTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private DashboardTools tools;

    @Autowired
    private McpSyncServer mcpServer;

    @Test
    void mcpServerExposesAllFourChartPilotTools() {
        List<String> toolNames = mcpServer.listTools().stream().map(McpSchema.Tool::name).toList();

        assertThat(toolNames).contains("createSession", "getSessionStatus", "getDashboardContext", "requestChart");
    }

    @Test
    void sseEndpointPublishesMessageEndpoint() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/sse"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<Stream<String>> response =
                HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofLines());

        assertThat(response.statusCode()).isEqualTo(200);
        try (Stream<String> lines = response.body()) {
            var iterator = lines.iterator();
            String eventLine = iterator.next();
            String dataLine = iterator.next();
            assertThat(eventLine).contains("endpoint");
            assertThat(dataLine).contains("/mcp/message");
        }
    }

    @Test
    void fullCycleCreateSessionWebSocketConnectContextAndChart() throws Exception {
        SessionCreated created = (SessionCreated) tools.createSession();
        String sessionId = created.sessionId();
        assertThat(created.status()).isEqualTo("WAITING_FOR_BROWSER");
        assertThat(((SessionStatusView) tools.getSessionStatus(sessionId)).status()).isEqualTo("WAITING_FOR_BROWSER");

        BrowserSimulator browser = new BrowserSimulator(sessionId);
        CompletableFuture<WebSocketSession> connection = new StandardWebSocketClient()
                .execute(browser, "ws://localhost:{port}/ws/dashboard?sessionId={sessionId}", port, sessionId);
        WebSocketSession browserSession = connection.get(10, TimeUnit.SECONDS);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(((SessionStatusView) tools.getSessionStatus(sessionId)).status()).isEqualTo("CONNECTED"));

        CommandOutcome context = (CommandOutcome) tools.getDashboardContext(sessionId);
        assertThat(context.status()).isEqualTo("ok");
        assertThat(context.details()).containsEntry("url", "http://osd.local/app/home?sessionId=" + sessionId);
        JsonNode contextCommand = browser.takeCommand();
        assertThat(contextCommand.path("action").asText()).isEqualTo("GET_CONTEXT");
        assertThat(contextCommand.path("correlationId").asText()).isNotBlank();

        String prompt = "Построй столбчатый график суммы заказов по категориям из индекса pg-orders";
        CommandOutcome chart = (CommandOutcome) tools.requestChart(sessionId, prompt);
        assertThat(chart.status()).isEqualTo("ok");
        assertThat(chart.message()).isEqualTo("График построен");
        JsonNode chartCommand = browser.takeCommand();
        assertThat(chartCommand.path("action").asText()).isEqualTo("RUN_AG_UI_PROMPT");
        assertThat(chartCommand.path("payload").path("prompt").asText()).isEqualTo(prompt);
        assertThat(chartCommand.path("correlationId").asText()).isNotBlank();

        browserSession.close(CloseStatus.NORMAL);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(((SessionStatusView) tools.getSessionStatus(sessionId)).status()).isEqualTo("DISCONNECTED"));

        CommandOutcome afterDisconnect = (CommandOutcome) tools.requestChart(sessionId, "ещё один график");
        assertThat(afterDisconnect.status()).isEqualTo("error");
        assertThat(afterDisconnect.code()).isEqualTo("BROWSER_NOT_CONNECTED");
    }

    @Test
    void malformedWsFrameDoesNotBreakSession() throws Exception {
        SessionCreated created = (SessionCreated) tools.createSession();
        String sessionId = created.sessionId();
        BrowserSimulator browser = new BrowserSimulator(sessionId);
        WebSocketSession browserSession = new StandardWebSocketClient()
                .execute(browser, "ws://localhost:{port}/ws/dashboard?sessionId={sessionId}", port, sessionId)
                .get(10, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(((SessionStatusView) tools.getSessionStatus(sessionId)).status()).isEqualTo("CONNECTED"));

        browserSession.sendMessage(new TextMessage("this is not json {{{"));
        browserSession.sendMessage(new TextMessage("{\"noCorrelationId\": true}"));

        CommandOutcome context = (CommandOutcome) tools.getDashboardContext(sessionId);
        assertThat(context.status()).isEqualTo("ok");
        assertThat(browserSession.isOpen()).isTrue();
        browserSession.close(CloseStatus.NORMAL);
    }

    @Test
    void requestChartWithInvalidSessionIdDoesNotBreakServer() {
        CommandOutcome result = (CommandOutcome) tools.requestChart("no-such-id", "график");

        assertThat(result.status()).isEqualTo("error");
        assertThat(result.code()).isEqualTo("NOT_FOUND");
        assertThat(((SessionCreated) tools.createSession()).status()).isEqualTo("WAITING_FOR_BROWSER");
        assertThat(((SessionStatusView) tools.getSessionStatus("no-such-id")).status()).isEqualTo("NOT_FOUND");
    }

    @Test
    void webSocketWithUnknownSessionIsRejected() throws Exception {
        CompletableFuture<WebSocketSession> connection = new StandardWebSocketClient()
                .execute(new TextWebSocketHandler() {
                }, "ws://localhost:{port}/ws/dashboard?sessionId={sessionId}", port, "unknown1");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(connection).isCompleted());
        WebSocketSession session = connection.getNow(null);
        if (session != null) {
            await().atMost(Duration.ofSeconds(5)).until(() -> !session.isOpen());
        }
    }

    private static final class BrowserSimulator extends TextWebSocketHandler {

        private final String sessionId;
        private final BlockingQueue<JsonNode> commands = new LinkedBlockingQueue<>();

        private BrowserSimulator(String sessionId) {
            this.sessionId = sessionId;
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
            JsonNode command = MAPPER.readTree(message.getPayload());
            commands.add(command);
            String action = command.path("action").asText();
            String correlationId = command.path("correlationId").asText();
            Map<String, Object> result = switch (action) {
                case "GET_CONTEXT" -> Map.of(
                        "status", "ok",
                        "message", "Контекст страницы OSD",
                        "details", Map.of(
                                "url", "http://osd.local/app/home?sessionId=" + sessionId,
                                "title", "OpenSearch Dashboards"));
                case "RUN_AG_UI_PROMPT" -> Map.of(
                        "status", "ok",
                        "message", "График построен",
                        "details", Map.of("chart", "bar", "index", "pg-orders"));
                default -> Map.of("status", "error", "message", "Unknown action: " + action);
            };
            String reply = MAPPER.writeValueAsString(Map.of("correlationId", correlationId, "result", result));
            session.sendMessage(new TextMessage(reply));
        }

        JsonNode takeCommand() throws InterruptedException {
            JsonNode command = commands.poll(10, TimeUnit.SECONDS);
            assertThat(command).as("browser should receive a command").isNotNull();
            return command;
        }
    }
}