package com.chartpilot.bridge.tools;

import java.util.Map;

import com.chartpilot.bridge.config.BridgeProperties;
import com.chartpilot.bridge.session.DashboardSession;
import com.chartpilot.bridge.session.DashboardSessionRegistry;
import com.chartpilot.bridge.session.SessionLimitExceededException;
import com.chartpilot.bridge.session.SessionStatus;
import com.chartpilot.bridge.tools.ToolResponse.CommandOutcome;
import com.chartpilot.bridge.tools.ToolResponse.SessionCreated;
import com.chartpilot.bridge.tools.ToolResponse.SessionStatusView;
import com.chartpilot.bridge.ws.BrowserNotConnectedException;
import com.chartpilot.bridge.ws.CommandGateway;
import com.chartpilot.bridge.ws.UnknownSessionException;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class DashboardTools {

    private static final int MAX_PROMPT_LENGTH = 4000;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final DashboardSessionRegistry registry;
    private final CommandGateway gateway;
    private final BridgeProperties properties;
    private final ObjectMapper mapper;

    public DashboardTools(
            DashboardSessionRegistry registry,
            CommandGateway gateway,
            BridgeProperties properties,
            ObjectMapper mapper) {
        this.registry = registry;
        this.gateway = gateway;
        this.properties = properties;
        this.mapper = mapper;
    }

    @McpTool(
            name = "createSession",
            description = "Создаёт сессию визуализации ChartPilot и возвращает sessionId, статус и кликабельную ссылку "
                    + "на OpenSearch Dashboards, которую пользователь должен открыть в браузере.")
    public ToolResponse createSession() {
        try {
            DashboardSession session = registry.create();
            return new SessionCreated(session.id(), properties.sessionUrl(session.id()), session.status().name());
        } catch (SessionLimitExceededException e) {
            return CommandOutcome.error(null, "SESSION_LIMIT", e.getMessage());
        }
    }

    @McpTool(
            name = "getSessionStatus",
            description = "Возвращает статус сессии: WAITING_FOR_BROWSER, CONNECTED, DISCONNECTED или NOT_FOUND.")
    public ToolResponse getSessionStatus(
            @McpToolParam(description = "Идентификатор сессии из createSession", required = true) String sessionId) {
        String status = registry.find(sessionId)
                .map(session -> session.status().name())
                .orElse("NOT_FOUND");
        return new SessionStatusView(sessionId, status);
    }

    @McpTool(
            name = "getDashboardContext",
            description = "Запрашивает у открытой вкладки OpenSearch Dashboards текущий контекст страницы "
                    + "(URL, заголовок, активный временной диапазон). Таймаут ожидания браузера — 15 секунд.")
    public ToolResponse getDashboardContext(
            @McpToolParam(description = "Идентификатор сессии из createSession", required = true) String sessionId) {
        try {
            JsonNode result = gateway.send(sessionId, "GET_CONTEXT", Map.of(), properties.contextTimeout());
            return outcome(sessionId, result);
        } catch (UnknownSessionException e) {
            return notFound(sessionId);
        } catch (BrowserNotConnectedException e) {
            return notConnected(sessionId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CommandOutcome.error(sessionId, "INTERRUPTED", "Запрос прерван");
        } catch (Exception e) {
            return CommandOutcome.error(sessionId, "COMMAND_FAILED", e.getMessage());
        }
    }

    @McpTool(
            name = "requestChart",
            description = "Отправляет промпт на естественном языке во встроенный AG-UI чат открытой вкладки "
                    + "OpenSearch Dashboards и ждёт построения графика (до 60 секунд). Требует подключённого браузера: "
                    + "если статус WAITING_FOR_BROWSER, сначала попросите пользователя открыть ссылку из createSession.")
    public ToolResponse requestChart(
            @McpToolParam(description = "Идентификатор сессии из createSession", required = true) String sessionId,
            @McpToolParam(description = "Промпт для AG-UI агента, например: покажи сумму заказов по категориям из pg-orders",
                    required = true) String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return CommandOutcome.error(sessionId, "INVALID_ARGUMENT", "prompt не может быть пустым");
        }
        if (prompt.length() > MAX_PROMPT_LENGTH) {
            return CommandOutcome.error(sessionId, "INVALID_ARGUMENT",
                    "prompt длиннее " + MAX_PROMPT_LENGTH + " символов");
        }
        try {
            JsonNode result = gateway.send(
                    sessionId, "RUN_AG_UI_PROMPT", Map.of("prompt", prompt), properties.chartTimeout());
            return outcome(sessionId, result);
        } catch (UnknownSessionException e) {
            return notFound(sessionId);
        } catch (BrowserNotConnectedException e) {
            return notConnected(sessionId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CommandOutcome.error(sessionId, "INTERRUPTED", "Запрос прерван");
        } catch (Exception e) {
            return CommandOutcome.error(sessionId, "COMMAND_FAILED", e.getMessage());
        }
    }

    private CommandOutcome outcome(String sessionId, JsonNode result) {
        if (result == null || result.isMissingNode() || result.isNull()) {
            return CommandOutcome.ok(sessionId, "", null);
        }
        Map<String, Object> details = result.has("details") && !result.path("details").isNull()
                ? mapper.convertValue(result.path("details"), MAP_TYPE)
                : null;
        return new CommandOutcome(
                sessionId,
                result.path("status").asText("ok"),
                result.path("message").asText(""),
                details,
                null,
                null,
                null);
    }

    private CommandOutcome notFound(String sessionId) {
        return CommandOutcome.error(sessionId, "NOT_FOUND",
                "Сессия '" + sessionId + "' не найдена или истекла. Вызовите createSession() и откройте новую ссылку.");
    }

    private CommandOutcome notConnected(String sessionId) {
        String url = properties.sessionUrl(sessionId);
        String sessionStatus = registry.find(sessionId)
                .map(session -> session.status().name())
                .orElse(SessionStatus.DISCONNECTED.name());
        return new CommandOutcome(sessionId, "error",
                "Браузер ещё не подключён. Откройте ссылку " + url + " и повторите запрос.",
                null, "BROWSER_NOT_CONNECTED", sessionStatus, url);
    }
}