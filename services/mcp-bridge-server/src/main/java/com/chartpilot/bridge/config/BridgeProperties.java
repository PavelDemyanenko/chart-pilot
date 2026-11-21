package com.chartpilot.bridge.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.web.util.UriComponentsBuilder;

@ConfigurationProperties(prefix = "chartpilot")
public record BridgeProperties(
        String osdPublicUrl,
        String bridgePublicWsUrl,
        Duration sessionTtl,
        Duration contextTimeout,
        Duration chartTimeout,
        List<String> allowedOrigins,
        int maxSessions) {

    public BridgeProperties {
        if (osdPublicUrl == null || osdPublicUrl.isBlank()) {
            osdPublicUrl = "http://localhost:5601";
        }
        if (bridgePublicWsUrl == null || bridgePublicWsUrl.isBlank()) {
            bridgePublicWsUrl = "ws://localhost:8080";
        }
        if (sessionTtl == null || sessionTtl.isNegative() || sessionTtl.isZero()) {
            sessionTtl = Duration.ofHours(1);
        }
        if (contextTimeout == null || contextTimeout.isNegative() || contextTimeout.isZero()) {
            contextTimeout = Duration.ofSeconds(15);
        }
        if (chartTimeout == null || chartTimeout.isNegative() || chartTimeout.isZero()) {
            chartTimeout = Duration.ofSeconds(60);
        }
        if (allowedOrigins == null || allowedOrigins.isEmpty()) {
            allowedOrigins = List.of("http://127.0.0.1:30560", "http://localhost:30560");
        }
        if (maxSessions <= 0) {
            maxSessions = 10_000;
        }
    }

    public String sessionUrl(String sessionId) {
        String base = osdPublicUrl.endsWith("/")
                ? osdPublicUrl.substring(0, osdPublicUrl.length() - 1)
                : osdPublicUrl;
        return UriComponentsBuilder.fromUriString(base + "/app/home")
                .queryParam("sessionId", sessionId)
                .queryParam("bridgeUrl", bridgePublicWsUrl)
                .encode()
                .toUriString();
    }
}