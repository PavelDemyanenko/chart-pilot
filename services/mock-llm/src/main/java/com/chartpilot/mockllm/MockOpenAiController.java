package com.chartpilot.mockllm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.ObjectMapper;

/**
 * Детерминированный OpenAI-совместимый мок для E2E без платных ключей (docs/WIREMOCK.md).
 * Двухшаговый цикл агента: пока в messages нет role=tool — просит вызвать SearchIndexTool
 * по индексу pg-orders; после результата инструмента — финальный ответ.
 * Поддерживает stream=false (JSON) и stream=true (SSE).
 */
@RestController
@RequestMapping("/v1/chat")
public class MockOpenAiController {

    private static final String TOOL_CALL_ARGUMENTS =
            "{\"index\":\"pg-orders\",\"query\":{\"size\":5,\"query\":{\"match_all\":{}}}}";
    private static final String TIMESERIES_TOOL_CALL_ARGUMENTS =
            "{\"index\":\"pg-orders\",\"query\":{\"size\":0,\"aggs\":{\"per_day\":"
                    + "{\"date_histogram\":{\"field\":\"created_at\",\"calendar_interval\":\"day\"},"
                    + "\"aggs\":{\"total\":{\"sum\":{\"field\":\"amount\"}}}}}}}";
    private static final String CATEGORICAL_TOOL_CALL_ARGUMENTS =
            "{\"index\":\"pg-orders\",\"query\":{\"size\":0,\"aggs\":{\"by_category\":"
                    + "{\"terms\":{\"field\":\"category\"},"
                    + "\"aggs\":{\"total\":{\"sum\":{\"field\":\"amount\"}}}}}}}";
    private static final String FINAL_TEXT =
            "График по индексу `pg-orders` успешно построен на основе данных из PostgreSQL.";

    private final ObjectMapper mapper;

    public MockOpenAiController(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @PostMapping("/completions")
    @SuppressWarnings("unchecked")
    public void chatCompletions(@RequestBody Map<String, Object> request, HttpServletResponse response)
            throws IOException {
        boolean stream = Boolean.TRUE.equals(request.get("stream"));
        List<Map<String, Object>> messages =
                (List<Map<String, Object>>) request.getOrDefault("messages", List.of());
        boolean hasToolResult = messages.stream().anyMatch(message -> "tool".equals(message.get("role")));
        String toolArguments = toolArguments(messages);

        if (stream) {
            response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            sseResponse(hasToolResult, toolArguments).writeTo(response.getOutputStream());
            response.flushBuffer();
            return;
        }
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), jsonResponse(hasToolResult, toolArguments));
    }

    /** Категориальный вопрос → terms-агрегация (inline bar/pie), иначе временной ряд. */
    private static String toolArguments(List<Map<String, Object>> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).get("role"))) {
                String text = String.valueOf(messages.get(i).get("content")).toLowerCase();
                return text.contains("категор") || text.contains("регион")
                        ? CATEGORICAL_TOOL_CALL_ARGUMENTS
                        : TIMESERIES_TOOL_CALL_ARGUMENTS;
            }
        }
        return TOOL_CALL_ARGUMENTS;
    }

    private Map<String, Object> jsonResponse(boolean hasToolResult, String toolArguments) {
        Map<String, Object> message = hasToolResult
                ? Map.of("role", "assistant", "content", FINAL_TEXT)
                : Map.of(
                        "role", "assistant",
                        "tool_calls", List.of(Map.of(
                                "id", "call_mock_search_1",
                                "type", "function",
                                "function", Map.of(
                                        "name", "SearchIndexTool",
                                        "arguments", toolArguments))));

        return Map.of(
                "id", "chatcmpl-mock-001",
                "object", "chat.completion",
                "created", System.currentTimeMillis() / 1000,
                "model", "mock-gpt-4o",
                "choices", List.of(Map.of(
                        "index", 0,
                        "message", message,
                        "finish_reason", hasToolResult ? "stop" : "tool_calls")),
                "usage", Map.of("prompt_tokens", 10, "completion_tokens", 20, "total_tokens", 30));
    }

    private StreamingResponseBody sseResponse(boolean hasToolResult, String toolArguments) {
        return out -> {
            List<Map<String, Object>> chunks;
            if (!hasToolResult) {
                chunks = List.of(
                        sseChunk(Map.of(
                                "role", "assistant",
                                "tool_calls", List.of(Map.of(
                                        "index", 0,
                                        "id", "call_mock_search_1",
                                        "type", "function",
                                        "function", Map.of(
                                                "name", "SearchIndexTool",
                                                "arguments", toolArguments)))),
                                null),
                        sseChunk(Map.of(), "tool_calls"));
            } else {
                chunks = List.of(
                        sseChunk(Map.of("role", "assistant", "content", "График по индексу `pg-orders` "), null),
                        sseChunk(Map.of("content", "успешно построен на основе данных из PostgreSQL."), null),
                        sseChunk(Map.of(), "stop"));
            }
            for (Map<String, Object> chunk : chunks) {
                out.write(("data: " + mapper.writeValueAsString(chunk) + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        };
    }

    private Map<String, Object> sseChunk(Map<String, Object> delta, String finishReason) {
        // CODE-05: finish_reason=null в промежуточных чанках (OpenAI SSE spec);
        // Map.of не допускает null — используем HashMap.
        Map<String, Object> choice = new java.util.HashMap<>();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", finishReason);
        Map<String, Object> chunk = new java.util.HashMap<>();
        chunk.put("id", "chatcmpl-mock-001");
        chunk.put("object", "chat.completion.chunk");
        chunk.put("created", System.currentTimeMillis() / 1000);
        chunk.put("model", "mock-gpt-4o");
        chunk.put("choices", List.of(choice));
        return chunk;
    }
}