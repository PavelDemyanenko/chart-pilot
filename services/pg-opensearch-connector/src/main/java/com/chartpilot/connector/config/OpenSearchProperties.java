package com.chartpilot.connector.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "opensearch")
public record OpenSearchProperties(String url, String username, String password) {

    public OpenSearchProperties {
        if (url == null || url.isBlank()) {
            url = "http://localhost:9200";
        }
    }

    public boolean hasCredentials() {
        return username != null && !username.isBlank() && password != null;
    }

    @Override
    public String toString() {
        return "OpenSearchProperties[url=" + url + ", username=" + username + ", password=***]";
    }
}