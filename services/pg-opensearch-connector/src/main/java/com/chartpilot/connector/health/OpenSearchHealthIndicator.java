package com.chartpilot.connector.health;

import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.HealthStatus;
import org.opensearch.client.opensearch.cluster.HealthResponse;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("opensearch")
public class OpenSearchHealthIndicator implements HealthIndicator {

    private final OpenSearchClient client;

    public OpenSearchHealthIndicator(OpenSearchClient client) {
        this.client = client;
    }

    @Override
    public Health health() {
        try {
            HealthResponse response = client.cluster().health(request -> request.timeout(timeout -> timeout.time("2s")));
            Health.Builder builder = response.status() == HealthStatus.Red ? Health.down() : Health.up();
            return builder
                    .withDetail("cluster_name", response.clusterName())
                    .withDetail("status", response.status().jsonValue())
                    .build();
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}