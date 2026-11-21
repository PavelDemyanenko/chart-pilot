package com.chartpilot.agui.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "adapter")
public record AdapterProperties(
        String opensearchUrl,
        String connectorName,
        int maxIterations,
        String proxyHost,
        int proxyPort,
        String apiKey,
        String osdUrl,
        String osdPublicUrl) {

    public AdapterProperties {
        if (opensearchUrl == null || opensearchUrl.isBlank()) {
            opensearchUrl = "http://opensearch:9200";
        }
        if (connectorName == null || connectorName.isBlank()) {
            connectorName = "ChartPilot LLM Connector";
        }
        if (maxIterations <= 0) {
            maxIterations = 10;
        }
        if (proxyHost == null) {
            proxyHost = "";
        }
        if (apiKey == null) {
            apiKey = "";
        }
        if (osdUrl == null || osdUrl.isBlank()) {
            osdUrl = "http://opensearch-dashboards:5601";
        }
        // ссылки для браузера должны быть публичными; osdUrl — внутрикластерный адрес API
        if (osdPublicUrl == null || osdPublicUrl.isBlank()) {
            osdPublicUrl = osdUrl;
        }
    }

    public boolean hasProxy() {
        return !proxyHost.isBlank() && proxyPort > 0;
    }
}