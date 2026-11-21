package com.chartpilot.bridge.ws;

import com.chartpilot.bridge.config.BridgeProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/** WS-транспорт и его wiring живут в ws; config — только properties (без циклов). */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final DashboardWebSocketHandler handler;
    private final BridgeProperties properties;

    public WebSocketConfig(DashboardWebSocketHandler handler, BridgeProperties properties) {
        this.handler = handler;
        this.properties = properties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // SEC-01: белый список Origin вместо "*" (защита от CSWSH)
        registry.addHandler(handler, "/ws/dashboard")
                .setAllowedOrigins(properties.allowedOrigins().toArray(String[]::new));
    }

    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        // PERF-02: крупные ответы AG-UI не должны рвать WS кодом 1009
        container.setMaxTextMessageBufferSize(512 * 1024);
        container.setMaxBinaryMessageBufferSize(512 * 1024);
        container.setAsyncSendTimeout(10_000L);
        return container;
    }
}