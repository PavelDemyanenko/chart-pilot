package com.chartpilot.bridge.session;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import com.chartpilot.bridge.config.BridgeProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DashboardSessionRegistryTest {

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-20T00:00:00Z"));

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        void advance(Duration duration) {
            now.updateAndGet(instant -> instant.plus(duration));
        }
    }

    private final MutableClock clock = new MutableClock();
    private final BridgeProperties properties = new BridgeProperties(
            "http://osd:5601", "ws://bridge:8080", Duration.ofHours(1),
            Duration.ofSeconds(15), Duration.ofSeconds(60), null, 10_000);
    private final DashboardSessionRegistry registry =
            new DashboardSessionRegistry(properties, clock, new SimpleMeterRegistry());

    @Test
    void createProduces128BitUniqueWaitingSession() {
        DashboardSession first = registry.create();
        DashboardSession second = registry.create();

        assertThat(first.id()).hasSize(22);
        assertThat(second.id()).hasSize(22).isNotEqualTo(first.id());
        assertThat(first.status()).isEqualTo(SessionStatus.WAITING_FOR_BROWSER);
        assertThat(registry.find(first.id())).contains(first);
        assertThat(registry.find("missing")).isEmpty();
    }

    @Test
    void createRejectsWhenSessionLimitReached() {
        BridgeProperties tiny = new BridgeProperties(
                "http://osd:5601", "ws://bridge:8080", Duration.ofHours(1),
                Duration.ofSeconds(15), Duration.ofSeconds(60), null, 1);
        DashboardSessionRegistry limited = new DashboardSessionRegistry(tiny, clock, new SimpleMeterRegistry());
        limited.create();

        assertThatThrownBy(limited::create).isInstanceOf(SessionLimitExceededException.class);
    }

    @Test
    void attachAndDetachTransitionStatuses() {
        DashboardSession session = registry.create();
        WebSocketSession webSocket = mock(WebSocketSession.class);
        when(webSocket.getId()).thenReturn("ws-1");
        when(webSocket.isOpen()).thenReturn(true);

        assertThat(registry.attach(session.id(), webSocket)).isTrue();
        assertThat(session.status()).isEqualTo(SessionStatus.CONNECTED);

        registry.detach(session.id(), webSocket);
        assertThat(session.status()).isEqualTo(SessionStatus.DISCONNECTED);
        assertThat(session.webSocket()).isNull();
    }

    @Test
    void attachRejectsUnknownSession() {
        assertThat(registry.attach("missing", mock(WebSocketSession.class))).isFalse();
    }

    @Test
    void detachFailsPendingCommands() {
        DashboardSession session = registry.create();
        WebSocketSession webSocket = mock(WebSocketSession.class);
        when(webSocket.getId()).thenReturn("ws-1");
        registry.attach(session.id(), webSocket);
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        session.registerPending("corr-1", future);

        registry.detach(session.id(), webSocket);

        assertThatThrownBy(future::get)
                .isInstanceOf(ExecutionException.class)
                .hasMessageContaining("WebSocket closed before response");
    }

    @Test
    void newerConnectionReplacesPreviousOne() throws Exception {
        DashboardSession session = registry.create();
        WebSocketSession previous = mock(WebSocketSession.class);
        when(previous.getId()).thenReturn("ws-old");
        when(previous.isOpen()).thenReturn(true);
        WebSocketSession current = mock(WebSocketSession.class);
        when(current.getId()).thenReturn("ws-new");

        registry.attach(session.id(), previous);
        registry.attach(session.id(), current);

        verify(previous).close(any(CloseStatus.class));
        assertThat(session.webSocket()).isSameAs(current);
        assertThat(session.status()).isEqualTo(SessionStatus.CONNECTED);
    }

    @Test
    void expiredSessionsAreEvicted() {
        BridgeProperties shortTtl = new BridgeProperties(
                "http://osd:5601", "ws://bridge:8080", Duration.ofMinutes(5),
                Duration.ofSeconds(15), Duration.ofSeconds(60), null, 10_000);
        DashboardSessionRegistry shortLived = new DashboardSessionRegistry(shortTtl, clock, new SimpleMeterRegistry());
        DashboardSession session = shortLived.create();

        clock.advance(Duration.ofMinutes(6));
        shortLived.evictExpired();

        assertThat(shortLived.find(session.id())).isEmpty();
    }

    @Test
    void concurrentAttachDetachNeverLosesSessions() throws Exception {
        int threads = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                DashboardSession session = registry.create();
                WebSocketSession webSocket = mock(WebSocketSession.class);
                when(webSocket.getId()).thenReturn("ws-" + session.id());
                when(webSocket.isOpen()).thenReturn(true);
                registry.attach(session.id(), webSocket);
                CompletableFuture<JsonNode> future = new CompletableFuture<>();
                session.registerPending("corr", future);
                registry.detach(session.id(), webSocket);
                assertThat(future).isCompletedExceptionally();
            }));
        }
        for (Future<?> future : futures) {
            future.get();
        }
        executor.shutdown();

        assertThat(registry.size()).isEqualTo(threads);
    }
}