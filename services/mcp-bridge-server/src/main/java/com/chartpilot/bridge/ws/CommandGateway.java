package com.chartpilot.bridge.ws;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.chartpilot.bridge.session.DashboardSession;
import com.chartpilot.bridge.session.DashboardSessionRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class CommandGateway {

    private final DashboardSessionRegistry registry;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Timer commandTimer;

    public CommandGateway(DashboardSessionRegistry registry, ObjectMapper mapper, Clock clock, MeterRegistry metrics) {
        this.registry = registry;
        this.mapper = mapper;
        this.clock = clock;
        this.commandTimer = Timer.builder("chartpilot.ws.command.duration").register(metrics);
    }

    public JsonNode send(String sessionId, String action, Map<String, Object> payload, Duration timeout)
            throws IOException, InterruptedException {
        DashboardSession session = registry.find(sessionId)
                .orElseThrow(() -> new UnknownSessionException(sessionId));
        WebSocketSession webSocket = session.webSocket();
        if (webSocket == null || !webSocket.isOpen()) {
            throw new BrowserNotConnectedException(sessionId);
        }

        String correlationId = UUID.randomUUID().toString();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        session.registerPending(correlationId, future);
        try (MDC.MDCCloseable ignoredSession = MDC.putCloseable("sessionId", sessionId);
             MDC.MDCCloseable ignoredCorrelation = MDC.putCloseable("correlationId", correlationId)) {
            return commandTimer.record(() -> doSend(session, webSocket, correlationId, action, payload, timeout, future));
        } finally {
            session.removePending(correlationId);
        }
    }

    private JsonNode doSend(
            DashboardSession session,
            WebSocketSession webSocket,
            String correlationId,
            String action,
            Map<String, Object> payload,
            Duration timeout,
            CompletableFuture<JsonNode> future) {
        try {
            String frame = mapper.writeValueAsString(Map.of(
                    "correlationId", correlationId,
                    "action", action,
                    "payload", payload));
            // ConcurrentWebSocketSessionDecorator: потокобезопасно, с таймаутом и лимитом буфера
            webSocket.sendMessage(new TextMessage(frame));
            session.touch(clock.instant());
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new CommandTimeoutException(action, timeout);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException("Browser command failed: " + cause.getMessage(), cause);
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("Browser command failed: " + e.getMessage(), e);
        }
    }
}