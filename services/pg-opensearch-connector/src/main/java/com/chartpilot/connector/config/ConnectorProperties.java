package com.chartpilot.connector.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "connector")
public record ConnectorProperties(String indexName, int batchSize, long pollIntervalMs, int safetyLagSeconds) {

    public ConnectorProperties {
        if (indexName == null || indexName.isBlank()) {
            indexName = "pg-orders";
        }
        if (batchSize <= 0) {
            batchSize = 500;
        }
        if (pollIntervalMs <= 0) {
            pollIntervalMs = 5000;
        }
        if (safetyLagSeconds < 0) {
            safetyLagSeconds = 2;
        }
    }
}