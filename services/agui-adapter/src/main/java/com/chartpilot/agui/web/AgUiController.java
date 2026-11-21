package com.chartpilot.agui.web;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.chartpilot.agui.config.AdapterProperties;
import com.chartpilot.agui.llm.LlmClient;
import com.chartpilot.agui.tools.ToolExecutor;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * AG-UI фасад: принимает RunAgentInput, исполняет agent-loop
 * (LLM через коннектор + Backend tools в OpenSearch) и отдаёт SSE-события AG-UI.
 */
@RestController
public class AgUiController {

    private static final Logger log = LoggerFactory.getLogger(AgUiController.class);

    private final LlmClient llm;
    private final ToolExecutor tools;
    private final AdapterProperties properties;
    private final ObjectMapper mapper;
    private final com.chartpilot.agui.llm.SchemaProvider schemaProvider;

    /**
     * История диалога per-threadId: chat OSD передаёт в RunAgentInput только
     * последнее сообщение, поэтому multi-turn контекст ведём сами. LRU на 1000 тредов.
     */
    private final Map<String, List<Map<String, Object>>> threads =
            Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<Map<String, Object>>> eldest) {
                    return size() > 1000;
                }
            });

    public AgUiController(LlmClient llm, ToolExecutor tools, AdapterProperties properties, ObjectMapper mapper,
            com.chartpilot.agui.llm.SchemaProvider schemaProvider) {
        this.llm = llm;
        this.tools = tools;
        this.properties = properties;
        this.mapper = mapper;
        this.schemaProvider = schemaProvider;
    }

    @PostMapping("/agui")
    @SuppressWarnings("unchecked")
    public void run(@RequestBody Map<String, Object> input, HttpServletResponse response) throws IOException {
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-cache");
        OutputStream out = response.getOutputStream();

        String threadId = String.valueOf(input.getOrDefault("threadId", UUID.randomUUID().toString()));
        String runId = String.valueOf(input.getOrDefault("runId", UUID.randomUUID().toString()));
        List<Map<String, Object>> messages = new ArrayList<>();
        Object rawMessages = input.get("messages");
        if (rawMessages instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> message) {
                    Map<String, Object> converted = new HashMap<>();
                    Object role = message.get("role");
                    Object content = message.get("content");
                    converted.put("role", role != null ? String.valueOf(role) : "user");
                    converted.put("content", content != null ? String.valueOf(content) : "");
                    messages.add(converted);
                }
            }
        }

        try {
            log.info("AG-UI run started: threadId={}, runId={}, messages={}", threadId, runId, messages.size());
            // Схема индекса в system-prompt: иначе LLM выдумывает индексы/поля и неверный DSL
            Map<String, Object> systemMessage = new HashMap<>();
            systemMessage.put("role", "system");
            systemMessage.put("content", schemaProvider.systemPrompt());
            // Continuation-run после возврата результата graph_timeseries_data / chart_pilot_chart
            // клиентом: текст уже был в первом run — не дублируем. Проверяем ТОЛЬКО входящие
            // сообщения: маркеры в истории треда не должны подавлять новые ходы.
            boolean continuation = false;
            for (Map<String, Object> message : messages) {
                if ("tool".equals(message.get("role"))) {
                    String content = String.valueOf(message.get("content"));
                    if (content.contains("graphData") || content.contains("chartPilotChart")) {
                        continuation = true;
                    }
                }
            }
            List<Map<String, Object>> history = threads.computeIfAbsent(threadId, k -> new ArrayList<>());
            List<Map<String, Object>> llmMessages = new ArrayList<>();
            int snapshotSize;
            synchronized (history) {
                if (!continuation) {
                    for (Map<String, Object> message : messages) {
                        if ("user".equals(message.get("role"))) {
                            history.add(message);
                        }
                    }
                }
                trimHistory(history);
                llmMessages.add(systemMessage);
                llmMessages.addAll(history);
                snapshotSize = llmMessages.size();
            }
            emit(out, Map.of("type", "RUN_STARTED", "threadId", threadId, "runId", runId));
            // CreateVisualizationTool доступен только при явном намерении сохранить:
            // иначе LLM (особенно с историей) создаёт saved-визуализацию вместо inline-графика
            List<Map<String, Object>> toolDefinitions = new ArrayList<>(ToolExecutor.toolDefinitions());
            if (!inferSaveIntent(messages)) {
                toolDefinitions.removeIf(definition ->
                        "CreateVisualizationTool".equals(((Map<?, ?>) definition.get("function")).get("name")));
            }
            String finalText = continuation ? null : agentLoop(llmMessages, out, toolDefinitions);
            synchronized (history) {
                for (int i = snapshotSize; i < llmMessages.size(); i++) {
                    history.add(llmMessages.get(i));
                }
                if (finalText != null && !finalText.isEmpty()) {
                    history.add(Map.of("role", "assistant", "content", finalText));
                }
                trimHistory(history);
            }
            if (finalText != null && !finalText.isEmpty()) {
                String messageId = UUID.randomUUID().toString();
                emit(out, Map.of("type", "TEXT_MESSAGE_START", "messageId", messageId, "role", "assistant"));
                for (int i = 0; i < finalText.length(); i += 200) {
                    emit(out, Map.of("type", "TEXT_MESSAGE_CONTENT", "messageId", messageId,
                            "delta", finalText.substring(i, Math.min(finalText.length(), i + 200))));
                }
                emit(out, Map.of("type", "TEXT_MESSAGE_END", "messageId", messageId));
            }
            emit(out, Map.of("type", "RUN_FINISHED", "threadId", threadId, "runId", runId));
        } catch (Exception e) {
            log.warn("AG-UI run failed: {}", e.getMessage());
            emit(out, Map.of("type", "RUN_ERROR", "message", String.valueOf(e.getMessage())));
        }
        out.flush();
    }

    private String agentLoop(List<Map<String, Object>> messages, OutputStream out,
            List<Map<String, Object>> toolDefinitions) throws Exception {
        for (int iteration = 0; iteration < properties.maxIterations(); iteration++) {
            JsonNode completion = llm.chat(messages, toolDefinitions);
            JsonNode message = completion.path("choices").path(0).path("message");
            JsonNode toolCalls = message.path("tool_calls");
            if (!toolCalls.isArray() || toolCalls.isEmpty()) {
                log.info("LLM final answer without tool calls (iteration {})", iteration);
                return message.path("content").asText("");
            }
            Map<String, Object> assistantMessage = new HashMap<>();
            assistantMessage.put("role", "assistant");
            assistantMessage.put("content", message.path("content").asText(null));
            List<Map<String, Object>> openAiToolCalls = new ArrayList<>();
            for (JsonNode toolCall : toolCalls) {
                openAiToolCalls.add(Map.of(
                        "id", toolCall.path("id").asText(),
                        "type", "function",
                        "function", Map.of(
                                "name", toolCall.path("function").path("name").asText(),
                                "arguments", toolCall.path("function").path("arguments").asText("{}"))));
            }
            assistantMessage.put("tool_calls", openAiToolCalls);
            messages.add(assistantMessage);

            for (JsonNode toolCall : toolCalls) {
                String toolCallId = toolCall.path("id").asText();
                String toolName = toolCall.path("function").path("name").asText();
                String arguments = toolCall.path("function").path("arguments").asText("{}");
                log.info("Tool call: {} args={}", toolName,
                        arguments.length() > 500 ? arguments.substring(0, 500) + "…" : arguments);
                emit(out, Map.of("type", "TOOL_CALL_START", "toolCallId", toolCallId, "toolCallName", toolName));
                emit(out, Map.of("type", "TOOL_CALL_ARGS", "toolCallId", toolCallId, "delta", arguments));
                String result;
                try {
                    result = tools.execute(toolName, mapper.readTree(arguments));
                } catch (Exception e) {
                    log.warn("Tool {} failed", toolName, e);
                    result = "{\"error\":\"" + e.getMessage() + "\"}";
                }
                emit(out, Map.of("type", "TOOL_CALL_END", "toolCallId", toolCallId));
                // Клиент ждёт TOOL_CALL_RESULT и для backend-tools: иначе input остаётся
                // в состоянии "Waiting for tool execution...". В транспорт идёт обрезка,
                // извлечение графиков ниже работает с полным валидным JSON.
                String transport = ToolExecutor.truncate(result);
                emit(out, Map.of("type", "TOOL_CALL_RESULT", "toolCallId", toolCallId, "content", transport));
                messages.add(Map.of("role", "tool", "tool_call_id", toolCallId, "content", transport));
                if ("SearchIndexTool".equals(toolName)) {
                    // График рисуем ТОЛЬКО по временному ряду из реального результата запроса
                    // (date_histogram): иначе каждый вопрос показывал бы один и тот же график.
                    ToolExecutor.Timeseries series = tools.extractTimeseries(mapper.readTree(result));
                    if (series != null) {
                        String graphCallId = UUID.randomUUID().toString();
                        Map<String, Object> graphArgs = new HashMap<>();
                        graphArgs.put("data", series.points());
                        graphArgs.put("title", series.title() + " (pg-orders)");
                        graphArgs.put("xAxisLabel", "Дата");
                        graphArgs.put("yAxisLabel", series.title());
                        emit(out, Map.of("type", "TOOL_CALL_START", "toolCallId", graphCallId,
                                "toolCallName", "graph_timeseries_data"));
                        emit(out, Map.of("type", "TOOL_CALL_ARGS", "toolCallId", graphCallId,
                                "delta", mapper.writeValueAsString(graphArgs)));
                        emit(out, Map.of("type", "TOOL_CALL_END", "toolCallId", graphCallId));
                        // В OSD 3.10.0 hook graph_timeseries_data не зарегистрирован: клиент ждёт
                        // TOOL_CALL_RESULT от агента и рендерит график из result.graphData.
                        Map<String, Object> graphResult = new HashMap<>();
                        graphResult.put("success", true);
                        graphResult.put("dataPoints", series.points().size());
                        graphResult.put("title", graphArgs.get("title"));
                        graphResult.put("message", "Successfully created timeseries graph");
                        graphResult.put("graphData", graphArgs);
                        emit(out, Map.of("type", "TOOL_CALL_RESULT", "toolCallId", graphCallId,
                                "content", mapper.writeValueAsString(graphResult)));
                    } else {
                        // Категориальная агрегация (terms): inline bar/pie через стороннюю
                        // библиотеку ECharts в plugin-action chart_pilot_chart.
                        ToolExecutor.Categorical categorical = tools.extractCategorical(mapper.readTree(result));
                        if (categorical != null) {
                            String chartCallId = UUID.randomUUID().toString();
                            Map<String, Object> chartArgs = new HashMap<>();
                            chartArgs.put("chart_type", inferChartType(messages));
                            chartArgs.put("title", categorical.title() + " (pg-orders)");
                            chartArgs.put("data", categorical.points());
                            emit(out, Map.of("type", "TOOL_CALL_START", "toolCallId", chartCallId,
                                    "toolCallName", "chart_pilot_chart"));
                            emit(out, Map.of("type", "TOOL_CALL_ARGS", "toolCallId", chartCallId,
                                    "delta", mapper.writeValueAsString(chartArgs)));
                            emit(out, Map.of("type", "TOOL_CALL_END", "toolCallId", chartCallId));
                            // TOOL_CALL_RESULT не эмитим: action зарегистрирован на клиенте,
                            // результат вернёт его handler (иначе задвоение результата).
                        }
                    }
                }
            }
        }
        throw new IllegalStateException("Agent loop exceeded " + properties.maxIterations() + " iterations");
    }

    private static boolean inferSaveIntent(List<Map<String, Object>> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (!"user".equals(messages.get(i).get("role"))) {
                continue;
            }
            String text = String.valueOf(messages.get(i).get("content")).toLowerCase();
            return text.contains("сохрани") || text.contains("сохранить")
                    || text.contains("создай визуализац") || text.contains("создать визуализац")
                    || text.contains("save") || text.contains("create visualization");
        }
        return false;
    }

    private static void trimHistory(List<Map<String, Object>> history) {
        while (history.size() > 40) {
            history.remove(0);
        }
    }

    private static String inferChartType(List<Map<String, Object>> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (!"user".equals(messages.get(i).get("role"))) {
                continue;
            }
            String text = String.valueOf(messages.get(i).get("content")).toLowerCase();
            if (text.contains("pie") || text.contains("круг") || text.contains("доля") || text.contains("donut")) {
                return "pie";
            }
            if (text.contains("линейн") || text.contains("line")) {
                return "line";
            }
            return "bar";
        }
        return "bar";
    }

    private void emit(OutputStream out, Map<String, Object> event) throws IOException {
        String data = mapper.writeValueAsString(event);
        out.write(("event: " + event.get("type") + "\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}