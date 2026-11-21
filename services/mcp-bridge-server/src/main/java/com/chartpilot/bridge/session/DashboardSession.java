package com.chartpilot.bridge.session;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.JsonNode;

/**
 * Состояние одной сессии. Все переходы — под per-session ReentrantLock (CODE-02):
 * attach/detach/evict/pending атомарны, зависших futures после обрыва не остаётся.
 */
public class DashboardSession {

    private final String id;
    private final Instant createdAt;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, CompletableFuture<JsonNode>> pending = new HashMap<>();

    private SessionStatus status = SessionStatus.WAITING_FOR_BROWSER;
    private Instant lastSeenAt;
    private WebSocketSession webSocket;

    public DashboardSession(String id, Instant createdAt) {
        this.id = id;
        this.createdAt = createdAt;
        this.lastSeenAt = createdAt;
    }

    public String id() {
        return id;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public SessionStatus status() {
        lock.lock();
        try {
            return status;
        } finally {
            lock.unlock();
        }
    }

    public Instant lastSeenAt() {
        lock.lock();
        try {
            return lastSeenAt;
        } finally {
            lock.unlock();
        }
    }

    public WebSocketSession webSocket() {
        lock.lock();
        try {
            return webSocket;
        } finally {
            lock.unlock();
        }
    }

    public void touch(Instant now) {
        lock.lock();
        try {
            lastSeenAt = now;
        } finally {
            lock.unlock();
        }
    }

    public WebSocketSession attach(WebSocketSession newWebSocket, Instant now) {
        lock.lock();
        try {
            WebSocketSession previous = webSocket;
            webSocket = newWebSocket;
            status = SessionStatus.CONNECTED;
            lastSeenAt = now;
            return previous;
        } finally {
            lock.unlock();
        }
    }

    public boolean detachIfCurrent(WebSocketSession expected, Instant now) {
        lock.lock();
        try {
            if (webSocket != expected) {
                return false;
            }
            webSocket = null;
            status = SessionStatus.DISCONNECTED;
            lastSeenAt = now;
            failAllPendingLocked("WebSocket closed before response");
            return true;
        } finally {
            lock.unlock();
        }
    }

    public boolean isExpired(Instant deadline) {
        lock.lock();
        try {
            return lastSeenAt.isBefore(deadline);
        } finally {
            lock.unlock();
        }
    }

    public void registerPending(String correlationId, CompletableFuture<JsonNode> future) {
        lock.lock();
        try {
            pending.put(correlationId, future);
        } finally {
            lock.unlock();
        }
    }

    public boolean completePending(String correlationId, JsonNode result) {
        lock.lock();
        try {
            CompletableFuture<JsonNode> future = pending.remove(correlationId);
            if (future == null) {
                return false;
            }
            future.complete(result);
            return true;
        } finally {
            lock.unlock();
        }
    }

    public void removePending(String correlationId) {
        lock.lock();
        try {
            pending.remove(correlationId);
        } finally {
            lock.unlock();
        }
    }

    public void failAllPending(String reason) {
        lock.lock();
        try {
            failAllPendingLocked(reason);
        } finally {
            lock.unlock();
        }
    }

    private void failAllPendingLocked(String reason) {
        for (CompletableFuture<JsonNode> future : pending.values()) {
            future.completeExceptionally(new IOException(reason));
        }
        pending.clear();
    }
}