package com.chartpilot.connector.sync;

import java.util.Map;

import com.chartpilot.connector.SharedContainers;
import com.chartpilot.connector.config.ConnectorProperties;
import com.chartpilot.connector.index.IndexInitializer;
import org.apache.hc.core5.http.HttpHost;
import org.junit.jupiter.api.Test;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Legacy concrete-индекс мигрирует в alias-схему reindex'ом без потери документов.
 * Отдельное имя индекса, чтобы не конфликтовать с pg-orders остальных тестов.
 */
class LegacyIndexMigrationTest {

    @Test
    void legacyConcreteIndexIsReindexedBehindAlias() throws Exception {
        OpenSearchClient client = new OpenSearchClient(ApacheHttpClient5TransportBuilder
                .builder(HttpHost.create(SharedContainers.openSearchUrl()))
                .setMapper(new JacksonJsonpMapper())
                .build());
        String alias = "legacy-orders";
        client.indices().create(request -> request.index(alias));
        client.index(request -> request.index(alias).id("1").document(Map.of("category", "books")));
        client.index(request -> request.index(alias).id("2").document(Map.of("category", "toys")));
        client.indices().refresh(request -> request.index(alias));

        new IndexInitializer(client, new ConnectorProperties(alias, 100, 5000, 2)).ensureIndex();

        assertThat(client.indices().existsAlias(request -> request.name(alias)).value()).isTrue();
        assertThat(client.indices().exists(request -> request.index(alias + "-v1")).value()).isTrue();
        assertThat(client.count(request -> request.index(alias)).count()).isEqualTo(2);
        assertThat(client.get(request -> request.index(alias).id("1"), Map.class).found()).isTrue();

        // повторный ensureIndex идемпотентен
        new IndexInitializer(client, new ConnectorProperties(alias, 100, 5000, 2)).ensureIndex();
        assertThat(client.count(request -> request.index(alias)).count()).isEqualTo(2);
    }
}