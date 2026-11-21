package com.chartpilot.bridge.session;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.chartpilot.bridge.config.BridgeProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

@Component
public class DashboardSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(DashboardSessionRegistry.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ConcurrentMap<String, DashboardSession> sessions = new ConcurrentHashMap<>();
    private final BridgeProperties properties;
    private final Clock clock;

    public DashboardSessionRegistry(BridgeProperties properties, Clock clock, MeterRegistry metrics) {
        this.properties = properties;
        this.clock = clock;
        Gauge.builder("chartpilot.sessions.active", this, DashboardSessionRegistry::size).register(metrics);
    }

    public DashboardSession create() {
        if (sessions.size() >= properties.maxSessions()) {
            throw new SessionLimitExceededException(properties.maxSessions());
        }
        while (true) {
            // 128 бит энтропии (SEC-02): Base64URL от 16 байт SecureRandom = 22 символа
            byte[] bytes = new byte[16];
            RANDOM.nextBytes(bytes);
            String id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            DashboardSession session = new DashboardSession(id, clock.instant());
            if (sessions.putIfAbsent(id, session) == null) {
                log.info("Session {} created (WAITING_FOR_BROWSER)", id);
                return session;
            }
        }
    }

    public Optional<DashboardSession> find(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    public boolean attach(String sessionId, WebSocketSession webSocket) {
        DashboardSession session = sessions.get(sessionId);
        if (session == null) {
            return false;
        }
        WebSocketSession previous = session.attach(webSocket, clock.instant());
        if (previous != null && previous.isOpen() && !previous.getId().equals(webSocket.getId())) {
            closeQuietly(previous, new CloseStatus(4002, "Replaced by a newer connection"));
        }
        log.info("Session {} CONNECTED (ws={})", sessionId, webSocket.getId());
        return true;
    }

    public void detach(String sessionId, WebSocketSession webSocket) {
        sessions.computeIfPresent(sessionId, (id, session) -> {
            if (session.detachIfCurrent(webSocket, clock.instant())) {
                log.info("Session {} DISCONNECTED (ws={})", id, webSocket.getId());
            }
            return session;
        });
    }

    public void touch(String sessionId) {
        DashboardSession session = sessions.get(sessionId);
        if (session != null) {
            session.touch(clock.instant());
        }
    }

    @Scheduled(fixedDelay = 60_000)
    public void evictExpired() {
        Instant deadline = clock.instant().minus(properties.sessionTtl());
        sessions.forEach((id, session) -> {
            if (!session.isExpired(deadline)) {
                return;
            }
            sessions.computeIfPresent(id, (key, current) -> {
                if (!current.isExpired(deadline)) {
                    return current;
                }
                WebSocketSession webSocket = current.webSocket();
                current.failAllPending("Session expired");
                if (webSocket != null && webSocket.isOpen()) {
                    closeQuietly(webSocket, new CloseStatus(4003, "Session expired"));
                }
                log.info("Session {} evicted (TTL {} exceeded)", key, properties.sessionTtl());
                return null;
            });
        });
    }

    public void closeAll(CloseStatus status, String reason) {
        sessions.forEach((id, session) -> {
            WebSocketSession webSocket = session.webSocket();
            session.failAllPending(reason);
            if (webSocket != null && webSocket.isOpen()) {
                closeQuietly(webSocket, status);
            }
        });
        sessions.clear();
    }

    public int size() {
        return sessions.size();
    }

    public void forEachConnected(java.util.function.Consumer<DashboardSession> action) {
        sessions.values().forEach(session -> {
            if (session.status() == SessionStatus.CONNECTED) {
                action.accept(session);
            }
        });
    }

    private static void closeQuietly(WebSocketSession webSocket, CloseStatus status) {
        try {
            webSocket.close(status);
        } catch (IOException e) {
            log.debug("Failed to close WebSocket {}: {}", webSocket.getId(), e.getMessage());
        }
    }
}