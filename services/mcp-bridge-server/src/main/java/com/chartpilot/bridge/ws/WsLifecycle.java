package com.chartpilot.bridge.ws;

import java.io.IOException;

import com.chartpilot.bridge.session.DashboardSessionRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * REL-03: heartbeat против idle-таймаутов Ingress и graceful shutdown WebSocket.
 */
@Component
public class WsLifecycle {

    private static final Logger log = LoggerFactory.getLogger(WsLifecycle.class);

    private final DashboardSessionRegistry registry;

    public WsLifecycle(DashboardSessionRegistry registry) {
        this.registry = registry;
    }

    @Scheduled(fixedRate = 25_000)
    public void heartbeat() {
        registry.forEachConnected(session -> {
            WebSocketSession webSocket = session.webSocket();
            if (webSocket != null && webSocket.isOpen()) {
                try {
                    webSocket.sendMessage(new PingMessage());
                } catch (IOException | IllegalStateException e) {
                    log.debug("Ping failed for session {}: {}", session.id(), e.getMessage());
                }
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        log.info("Closing active WebSocket sessions (GOING_AWAY)");
        registry.closeAll(CloseStatus.GOING_AWAY, "Server shutting down");
    }
}