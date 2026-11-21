package com.chartpilot.agui;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AgUiAdapterIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static HttpServer stub;
    private static int stubPort;
    private static volatile int lastMessageCount;
    private static volatile boolean lastHadPieHistory;
    private static volatile boolean lastPromptHadSchema;

    @Value("${local.server.port}")
    private int port;

    @BeforeAll
    static void startStub() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stubPort = stub.getAddress().getPort();
        stub.createContext("/v1/chat/completions", exchange -> {
            JsonNode request = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
            lastMessageCount = request.path("messages").size();
            lastHadPieHistory = request.path("messages").toString().contains("регионам (pie)");
            lastPromptHadSchema = request.path("messages").toString().contains("customer_region (keyword)");
            // tool-результат в конце = продолжение текущего цикла; tool в истории
            // предыдущих ходов не должен обрывать agent-loop
            JsonNode allMessages = request.path("messages");
            boolean hasToolResult = allMessages.size() > 0
                    && "tool".equals(allMessages.get(allMessages.size() - 1).path("role").asText());
            // маркеры сценариев ищем только в user-сообщениях: system-prompt содержит
            // похожие слова («вложенной sum», «категориальных») и ломает выбор ветки
            boolean noGraph = false;
            for (JsonNode message : request.path("messages")) {
                if ("user".equals(message.path("role").asText())
                        && message.path("content").asText("").contains("без графика")) {
                    noGraph = true;
                }
            }
            boolean categorical = false;
            boolean nested = false;
            for (JsonNode message : request.path("messages")) {
                if (!"user".equals(message.path("role").asText())) {
                    continue;
                }
                String content = message.path("content").asText("");
                if (content.contains("категориальный") || content.contains("категориям")) {
                    categorical = true;
                }
                if (content.contains("вложенн")) {
                    nested = true;
                }
            }
            String toolArguments;
            if (nested) {
                toolArguments = "{\\\"index\\\":\\\"pg-orders\\\",\\\"query\\\":{\\\"size\\\":0,\\\"aggs\\\":{\\\"categories\\\":"
                        + "{\\\"terms\\\":{\\\"field\\\":\\\"category\\\"},\\\"aggs\\\":{\\\"daily_amounts\\\":"
                        + "{\\\"date_histogram\\\":{\\\"field\\\":\\\"created_at\\\",\\\"calendar_interval\\\":\\\"day\\\"},"
                        + "\\\"aggs\\\":{\\\"total_amount\\\":{\\\"sum\\\":{\\\"field\\\":\\\"amount\\\"}}}}}}}}}";
            } else if (noGraph) {
                toolArguments = "{\\\"index\\\":\\\"pg-orders\\\",\\\"query\\\":{\\\"size\\\":5,\\\"query\\\":{\\\"match_all\\\":{}}}}";
            } else if (categorical) {
                toolArguments = "{\\\"index\\\":\\\"pg-orders\\\",\\\"query\\\":{\\\"size\\\":0,\\\"aggs\\\":{\\\"by_region\\\":"
                        + "{\\\"terms\\\":{\\\"field\\\":\\\"customer_region\\\"},"
                        + "\\\"aggs\\\":{\\\"total\\\":{\\\"sum\\\":{\\\"field\\\":\\\"amount\\\"}}}}}}}";
            } else {
                toolArguments = "{\\\"index\\\":\\\"pg-orders\\\",\\\"query\\\":{\\\"size\\\":0,\\\"aggs\\\":{\\\"per_day\\\":"
                        + "{\\\"date_histogram\\\":{\\\"field\\\":\\\"created_at\\\",\\\"calendar_interval\\\":\\\"day\\\"},"
                        + "\\\"aggs\\\":{\\\"total\\\":{\\\"sum\\\":{\\\"field\\\":\\\"amount\\\"}}}}}}}";
            }
            boolean createVis = false;
            for (JsonNode message : request.path("messages")) {
                if ("user".equals(message.path("role").asText())
                        && message.path("content").asText("").contains("bar chart")) {
                    createVis = true;
                }
            }
            String response;
            if (hasToolResult) {
                response = """
                        {"choices":[{"message":{"role":"assistant","content":"График по индексу `pg-orders` успешно построен."},"finish_reason":"stop"}]}
                        """;
            } else if (createVis) {
                String visTitle = noGraph || request.path("messages").toString().contains("reuse") ? "reuse" : "Сумма по категориям";
                response = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"call_vis\","
                        + "\"type\":\"function\",\"function\":{\"name\":\"CreateVisualizationTool\",\"arguments\":"
                        + "\"{\\\"title\\\":\\\"" + visTitle + "\\\",\\\"chart_type\\\":\\\"bar\\\","
                        + "\\\"x_field\\\":\\\"category\\\",\\\"y_agg\\\":\\\"sum\\\",\\\"y_field\\\":\\\"amount\\\"}\"}}]},"
                        + "\"finish_reason\":\"tool_calls\"}]}";
            } else {
                response = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"SearchIndexTool\",\"arguments\":\"" + toolArguments + "\"}}]},\"finish_reason\":\"tool_calls\"}]}";
            }
            respond(exchange, 200, response);
        });
        stub.createContext("/_plugins/_ml/connectors/_search", exchange -> respond(exchange, 200, """
                {"hits":{"hits":[{"_source":{"parameters":{"endpoint":"http://127.0.0.1:%d/v1/chat/completions","model":"mock-gpt-4o"},"credential":{"openAI_key":"mock-key"}}}]}}
                """.formatted(stubPort)));
        stub.createContext("/pg-orders/_search", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String response;
            if (body.contains("daily_amounts")) {
                // >6000 символов: регрессия на обрезку JSON (Jackson падал на '…')
                StringBuilder nestedAggs = new StringBuilder("{\"hits\":{\"total\":{\"value\":500},\"hits\":[]},\"aggregations\":{\"categories\":{\"buckets\":[");
                String[] categories = {"books", "clothing", "electronics", "groceries", "toys"};
                for (int c = 0; c < categories.length; c++) {
                    if (c > 0) {
                        nestedAggs.append(',');
                    }
                    nestedAggs.append("{\"key\":\"").append(categories[c]).append("\",\"doc_count\":100,\"daily_amounts\":{\"buckets\":[");
                    for (int d = 1; d <= 30; d++) {
                        if (d > 1) {
                            nestedAggs.append(',');
                        }
                        nestedAggs.append("{\"key_as_string\":\"2026-10-").append(String.format("%02d", d))
                                .append("T00:00:00.000Z\",\"doc_count\":3,\"total_amount\":{\"value\":100.5}}");
                    }
                    nestedAggs.append("]}}");
                }
                nestedAggs.append("]}}}");
                response = nestedAggs.toString();
            } else if (body.contains("date_histogram")) {
                response = "{\"hits\":{\"total\":{\"value\":500},\"hits\":[]},\"aggregations\":{\"per_day\":{\"buckets\":"
                        + "[{\"key_as_string\":\"2026-11-01T00:00:00.000Z\",\"value\":{\"value\":100.5}}]}}}";
            } else if (body.contains("aggs")) {
                response = "{\"hits\":{\"total\":{\"value\":500},\"hits\":[]},\"aggregations\":{"
                        + "\"by_region\":{\"buckets\":[{\"key\":\"EU\",\"doc_count\":10,\"value\":{\"value\":100.5}},"
                        + "{\"key\":\"US\",\"doc_count\":20,\"value\":{\"value\":200.5}}]},"
                        + "\"by_x\":{\"buckets\":[{\"key\":\"EU\",\"value\":{\"value\":100.5}}]}}}";
            } else {
                response = "{\"hits\":{\"total\":{\"value\":500},\"hits\":[]}}";
            }
            respond(exchange, 200, response);
        });
        // ключ maппинга — concrete-имя за alias'ом, как в реальном стенде
        stub.createContext("/pg-orders/_mapping", exchange -> respond(exchange, 200,
                "{\"pg-orders-v1\":{\"mappings\":{\"properties\":{\"amount\":{\"type\":\"scaled_float\"},"
                        + "\"created_at\":{\"type\":\"date\"},\"customer_region\":{\"type\":\"keyword\"},"
                        + "\"category\":{\"type\":\"keyword\"}}}}}"));
        stub.createContext("/api/saved_objects/_find", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            if (query != null && query.contains("type=visualization")) {
                String response = query.contains("search=reuse")
                        ? "{\"saved_objects\":[{\"id\":\"vis-9\"}]}"
                        : "{\"saved_objects\":[]}";
                respond(exchange, 200, response);
            } else {
                respond(exchange, 200, "{\"saved_objects\":[{\"id\":\"ip-1\"}]}");
            }
        });
        stub.createContext("/api/saved_objects/visualization", exchange -> respond(exchange, 200,
                "{\"id\":\"vis-1\"}"));
        stub.createContext("/api/saved_objects/index-pattern", exchange -> respond(exchange, 200,
                "{\"id\":\"ip-new\"}"));
        stub.start();
    }

    @AfterAll
    static void stopStub() {
        stub.stop(0);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("adapter.opensearch-url", () -> "http://127.0.0.1:" + stubPort);
        registry.add("adapter.osd-url", () -> "http://127.0.0.1:" + stubPort);
    }

    @Test
    void aguiRunEmitsToolGraphAndFinalTextInSecondRun() throws Exception {
        String run1 = MAPPER.writeValueAsString(Map.of(
                "threadId", "t-1",
                "runId", "r-1",
                "messages", List.of(Map.of("id", "m1", "role", "user", "content", "Построй график по pg-orders")),
                "tools", List.of(),
                "context", List.of(),
                "state", Map.of(),
                "forwardedProps", Map.of()));
        String sse1 = postAgui(run1);
        assertThat(sse1)
                .contains("RUN_STARTED")
                .contains("TOOL_CALL_START")
                .contains("SearchIndexTool")
                .contains("graph_timeseries_data")
                .contains("2026-11-01")
                .contains("TOOL_CALL_RESULT")
                .contains("graphData")
                .contains("TEXT_MESSAGE_CONTENT")
                .contains("успешно построен")
                .contains("RUN_FINISHED");

        // Continuation-run после возврата результата графика клиентом: текст не дублируется
        String run2 = MAPPER.writeValueAsString(Map.of(
                "threadId", "t-1",
                "runId", "r-2",
                "messages", List.of(
                        Map.of("id", "m1", "role", "user", "content", "Построй график по pg-orders"),
                        Map.of("id", "m2", "role", "tool", "tool_call_id", "graph-1",
                                "content", "{\"success\":true,\"graphData\":{\"data\":[]}}")),
                "tools", List.of(),
                "context", List.of(),
                "state", Map.of(),
                "forwardedProps", Map.of()));
        String sse2 = postAgui(run2);
        assertThat(sse2).contains("RUN_FINISHED");
        assertThat(sse2).doesNotContain("TEXT_MESSAGE_CONTENT");
    }

    @Test
    void nonTimeseriesSearchDoesNotEmitGraph() throws Exception {
        String run = MAPPER.writeValueAsString(Map.of(
                "threadId", "t-ng",
                "runId", "r-ng",
                "messages", List.of(Map.of("id", "m1", "role", "user", "content", "Суммы по регионам, без графика")),
                "tools", List.of(),
                "context", List.of(),
                "state", Map.of(),
                "forwardedProps", Map.of()));
        String sse = postAgui(run);
        assertThat(sse)
                .contains("SearchIndexTool")
                .contains("TEXT_MESSAGE_CONTENT")
                .contains("RUN_FINISHED");
        assertThat(sse).doesNotContain("graph_timeseries_data");
    }

    @Test
    void categoricalSearchEmitsChartPilotChartWithoutAgentResult() throws Exception {
        String run = MAPPER.writeValueAsString(Map.of(
                "threadId", "t-cat",
                "runId", "r-cat",
                "messages", List.of(Map.of("id", "m1", "role", "user",
                        "content", "Построй категориальный график суммы по регионам (pie)")),
                "tools", List.of(),
                "context", List.of(),
                "state", Map.of(),
                "forwardedProps", Map.of()));
        String sse = postAgui(run);
        assertThat(sse)
                .contains("chart_pilot_chart")
                // args внутри SSE экранированы как JSON-строка delta
                .contains("\\\"chart_type\\\":\\\"pie\\\"")
                .contains("EU")
                .contains("TEXT_MESSAGE_CONTENT")
                .contains("RUN_FINISHED");
        assertThat(sse).doesNotContain("graph_timeseries_data");
        // схема из mapping за alias'ом попала в system-prompt (регрессия )
        assertThat(lastPromptHadSchema).isTrue();
        // Без намерения сохранить CreateVisualizationTool недоступен LLM
        assertThat(sse).doesNotContain("CreateVisualizationTool");
        // Результат chart_pilot_chart возвращает клиентский handler: агент эмитит
        // TOOL_CALL_RESULT только для backend-tool SearchIndexTool
        assertThat(sse.split("\"type\":\"TOOL_CALL_RESULT\"", -1).length - 1).isEqualTo(1);

        // Continuation-run после результата клиентского handler: текст не дублируется
        String run2 = MAPPER.writeValueAsString(Map.of(
                "threadId", "t-cat",
                "runId", "r-cat2",
                "messages", List.of(
                        Map.of("id", "m1", "role", "user",
                                "content", "Построй категориальный график суммы по регионам (pie)"),
                        Map.of("id", "m2", "role", "tool", "tool_call_id", "chart-1",
                                "content", "{\"success\":true,\"chartPilotChart\":true,\"points\":2}")),
                "tools", List.of(),
                "context", List.of(),
                "state", Map.of(),
                "forwardedProps", Map.of()));
        String sse2 = postAgui(run2);
        assertThat(sse2).contains("RUN_FINISHED");
        assertThat(sse2).doesNotContain("TEXT_MESSAGE_CONTENT");
    }

    @Test
    void truncatedNestedAggsStillRenderChart() throws Exception {
        String run = MAPPER.writeValueAsString(Map.of(
                "threadId", "t-big",
                "runId", "r-big",
                "messages", List.of(Map.of("id", "m1", "role", "user",
                        "content", "Построй категориальный график с вложенной детализацией по дням (line)")),
                "tools", List.of(), "context", List.of(), "state", Map.of(), "forwardedProps", Map.of()));
        String sse = postAgui(run);
        assertThat(sse)
                .contains("chart_pilot_chart")
                .contains("\\\"chart_type\\\":\\\"line\\\"")
                .contains("(truncated)")
                .contains("RUN_FINISHED");
        assertThat(sse).doesNotContain("RUN_ERROR");
    }

    @Test
    void multiTurnSecondRunReceivesThreadHistory() throws Exception {
        String run1 = MAPPER.writeValueAsString(Map.of(
                "threadId", "t-mt",
                "runId", "r-mt1",
                "messages", List.of(Map.of("id", "m1", "role", "user",
                        "content", "Построй категориальный график суммы по регионам (pie)")),
                "tools", List.of(), "context", List.of(), "state", Map.of(), "forwardedProps", Map.of()));
        assertThat(postAgui(run1)).contains("chart_pilot_chart");

        // Второй ход того же треда: клиент передаёт ТОЛЬКО новое сообщение (как chat OSD),
        // историю адаптер подставляет сам
        String run2 = MAPPER.writeValueAsString(Map.of(
                "threadId", "t-mt",
                "runId", "r-mt2",
                "messages", List.of(Map.of("id", "m2", "role", "user",
                        "content", "А теперь переключи на линейный график по категориям")),
                "tools", List.of(), "context", List.of(), "state", Map.of(), "forwardedProps", Map.of()));
        String sse2 = postAgui(run2);
        assertThat(sse2)
                .contains("chart_pilot_chart")
                .contains("\\\"chart_type\\\":\\\"line\\\"")
                .contains("RUN_FINISHED");
        assertThat(lastMessageCount).isGreaterThanOrEqualTo(5);
        assertThat(lastHadPieHistory).isTrue();
    }

    @Test
    void createVisualizationToolReturnsUrl() throws Exception {
        String run = MAPPER.writeValueAsString(Map.of(
                "threadId", "t-vis",
                "runId", "r-vis",
                "messages", List.of(Map.of("id", "m1", "role", "user",
                        "content", "Сохрани визуализацию: bar chart суммы по категориям")),
                "tools", List.of(),
                "context", List.of(),
                "state", Map.of(),
                "forwardedProps", Map.of()));
        String sse = postAgui(run);
        assertThat(sse)
                .contains("CreateVisualizationTool")
                .contains("vis-1")
                .contains("/app/visualize#/edit/vis-1")
                .contains("now-30d")
                .contains("data_summary")
                .contains("RUN_FINISHED");
    }

    @Test
    void createVisualizationReusesExistingByTitle() throws Exception {
        String run = MAPPER.writeValueAsString(Map.of(
                "threadId", "t-reuse",
                "runId", "r-reuse",
                "messages", List.of(Map.of("id", "m1", "role", "user", "content", "Сохрани визуализацию bar chart reuse")),
                "tools", List.of(),
                "context", List.of(),
                "state", Map.of(),
                "forwardedProps", Map.of()));
        String sse = postAgui(run);
        assertThat(sse).contains("vis-9").doesNotContain("vis-1");
    }

    private String postAgui(String body) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/agui"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("text/event-stream");
        return response.body();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}