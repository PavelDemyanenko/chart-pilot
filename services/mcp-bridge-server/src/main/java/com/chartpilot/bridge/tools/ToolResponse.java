package com.chartpilot.bridge.tools;

import java.util.Map;

/**
 * Типизированные контракты ответов MCP-инструментов (ARCH-02).
 * Имена полей совпадают с прежним Map-контрактом — совместимость с клиентами сохранена.
 */
public sealed interface ToolResponse {

    record SessionCreated(String sessionId, String url, String status) implements ToolResponse {
    }

    record SessionStatusView(String sessionId, String status) implements ToolResponse {
    }

    record CommandOutcome(
            String sessionId,
            String status,
            String message,
            Map<String, Object> details,
            String code,
            String sessionStatus,
            String url) implements ToolResponse {

        static CommandOutcome ok(String sessionId, String message, Map<String, Object> details) {
            return new CommandOutcome(sessionId, "ok", message, details, null, null, null);
        }

        static CommandOutcome error(String sessionId, String code, String message) {
            return new CommandOutcome(sessionId, "error", message, null, code, null, null);
        }
    }
}