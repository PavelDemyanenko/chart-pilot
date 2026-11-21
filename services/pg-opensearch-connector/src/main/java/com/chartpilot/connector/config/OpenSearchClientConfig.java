package com.chartpilot.connector.config;

import java.net.URISyntaxException;
import java.time.Duration;

import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.util.Timeout;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenSearchClientConfig {

    @Bean(destroyMethod = "close")
    public OpenSearchTransport openSearchTransport(OpenSearchProperties properties) throws URISyntaxException {
        HttpHost host = HttpHost.create(properties.url());
        ApacheHttpClient5TransportBuilder builder = ApacheHttpClient5TransportBuilder.builder(host)
                .setMapper(new JacksonJsonpMapper())
                .setRequestConfigCallback(requestConfig -> requestConfig
                        .setResponseTimeout(Timeout.of(Duration.ofSeconds(30)))
                        .setConnectionRequestTimeout(Timeout.of(Duration.ofSeconds(5))))
                .setHttpClientConfigCallback(httpClient -> {
                    httpClient.setConnectionManager(PoolingAsyncClientConnectionManagerBuilder.create()
                            .setDefaultConnectionConfig(ConnectionConfig.custom()
                                    .setConnectTimeout(Timeout.of(Duration.ofSeconds(5)))
                                    .build())
                            .build());
                    if (properties.hasCredentials()) {
                        BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
                        credentialsProvider.setCredentials(
                                new AuthScope(host),
                                new UsernamePasswordCredentials(properties.username(), properties.password().toCharArray()));
                        httpClient.setDefaultCredentialsProvider(credentialsProvider);
                    }
                    return httpClient;
                });
        return builder.build();
    }

    @Bean
    public OpenSearchClient openSearchClient(OpenSearchTransport transport) {
        return new OpenSearchClient(transport);
    }
}