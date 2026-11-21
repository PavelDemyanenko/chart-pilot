package com.chartpilot.connector.index;

import java.io.IOException;

import com.chartpilot.connector.config.ConnectorProperties;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class IndexInitializer {

    private static final Logger log = LoggerFactory.getLogger(IndexInitializer.class);

    private final OpenSearchClient client;
    private final ConnectorProperties properties;
    private volatile boolean initialized;

    public IndexInitializer(OpenSearchClient client, ConnectorProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /**
     * Alias-схема: concrete-индекс pg-orders-v1 + alias pg-orders.
     * Legacy concrete pg-orders мигрирует reindex'ом один раз при старте.
     */
    public void ensureIndex() throws IOException {
        if (initialized) {
            return;
        }
        String alias = properties.indexName();
        String concrete = alias + "-v1";
        if (client.indices().existsAlias(request -> request.name(alias)).value()) {
            initialized = true;
            return;
        }
        if (client.indices().exists(request -> request.index(concrete)).value()) {
            // незавершённая ранее миграция: concrete есть, alias ещё нет
            putAlias(alias, concrete);
            initialized = true;
            return;
        }
        createConcreteIndex(concrete);
        if (client.indices().exists(request -> request.index(alias)).value()) {
            log.info("Migrating legacy concrete index '{}' to '{}' via _reindex", alias, concrete);
            client.reindex(request -> request
                    .source(source -> source.index(alias))
                    .dest(dest -> dest.index(concrete)));
            client.indices().refresh(request -> request.index(concrete));
            client.indices().delete(request -> request.index(alias));
        }
        putAlias(alias, concrete);
        initialized = true;
        log.info("OpenSearch index '{}' ready behind alias '{}' (amount=scaled_float)", concrete, alias);
    }

    private void putAlias(String alias, String concrete) throws IOException {
        client.indices().putAlias(request -> request.index(concrete).name(alias));
    }

    private void createConcreteIndex(String concrete) throws IOException {
        try {
            client.indices().create(request -> request
                    .index(concrete)
                    .mappings(mapping -> mapping
                            .properties("id", property -> property.long_(long_ -> long_))
                            .properties("customer_region", property -> property.keyword(keyword -> keyword))
                            .properties("category", property -> property.keyword(keyword -> keyword))
                            .properties("status", property -> property.keyword(keyword -> keyword))
                            .properties("amount", property -> property.scaledFloat(scaled -> scaled.scalingFactor(100.0)))
                            .properties("created_at", property -> property.date(date -> date))
                            .properties("updated_at", property -> property.date(date -> date))));
        } catch (OpenSearchException e) {
            if (e.getMessage() == null || !e.getMessage().contains("resource_already_exists_exception")) {
                throw e;
            }
        }
    }

    public void invalidate() {
        initialized = false;
    }
}