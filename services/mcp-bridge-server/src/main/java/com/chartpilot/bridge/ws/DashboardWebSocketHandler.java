package com.chartpilot.bridge.ws;

import com.chartpilot.bridge.session.DashboardSessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class DashboardWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(DashboardWebSocketHandler.class);
    private static final String SESSION_ID_ATTRIBUTE = "chartpilot.sessionId";
    private static final String DECORATED_ATTRIBUTE = "chartpilot.decoratedSession";

    private final DashboardSessionRegistry registry;
    private final ObjectMapper mapper;

    public DashboardWebSocketHandler(DashboardSessionRegistry registry, ObjectMapper mapper) {
        this.registry = registry;
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionId = sessionId(session);
        if (sessionId == null || sessionId.isBlank()) {
            session.close(new CloseStatus(4001, "Unknown or missing sessionId"));
            return;
        }
        // PERF-02: буферизованная отправка с таймаутом вместо голого synchronized
        ConcurrentWebSocketSessionDecorator decorated =
                new ConcurrentWebSocketSessionDecorator(session, 10_000, 512 * 1024);
        if (!registry.attach(sessionId, decorated)) {
            session.close(new CloseStatus(4001, "Unknown or missing sessionId"));
            return;
        }
        session.getAttributes().put(SESSION_ID_ATTRIBUTE, sessionId);
        session.getAttributes().put(DECORATED_ATTRIBUTE, decorated);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String sessionId = (String) session.getAttributes().get(SESSION_ID_ATTRIBUTE);
        if (sessionId == null) {
            return;
        }
        try (MDC.MDCCloseable ignored = MDC.putCloseable("sessionId", sessionId)) {
            JsonNode frame;
            try {
                frame = mapper.readTree(message.getPayload());
            } catch (Exception e) {
                log.debug("Ignoring malformed WS frame for session {}: {}", sessionId, e.getMessage());
                return;
            }
            String correlationId = frame.path("correlationId").asText(null);
            if (correlationId == null) {
                log.debug("Ignoring frame without correlationId for session {}", sessionId);
                return;
            }
            registry.find(sessionId).ifPresent(dashboard -> {
                dashboard.touch(java.time.Instant.now());
                if (!dashboard.completePending(correlationId, frame.path("result"))) {
                    log.debug("No pending command for correlationId {} (session {})", correlationId, sessionId);
                }
            });
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sessionId = (String) session.getAttributes().get(SESSION_ID_ATTRIBUTE);
        if (sessionId != null) {
            registry.detach(sessionId, decorated(session));
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        String sessionId = (String) session.getAttributes().get(SESSION_ID_ATTRIBUTE);
        log.warn("WebSocket transport error (session {}): {}", sessionId, exception.getMessage());
        if (sessionId != null) {
            registry.detach(sessionId, decorated(session));
        }
        try {
            session.close(CloseStatus.SERVER_ERROR);
        } catch (Exception e) {
            log.debug("Failed to close errored WebSocket: {}", e.getMessage());
        }
    }

    private static WebSocketSession decorated(WebSocketSession session) {
        Object decorated = session.getAttributes().get(DECORATED_ATTRIBUTE);
        return decorated instanceof WebSocketSession webSocketSession ? webSocketSession : session;
    }

    private static String sessionId(WebSocketSession session) {
        if (session.getUri() == null) {
            return null;
        }
        return UriComponentsBuilder.fromUri(session.getUri())
                .build()
                .getQueryParams()
                .getFirst("sessionId");
    }
}