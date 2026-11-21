package com.chartpilot.connector.sync;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import com.chartpilot.connector.SharedContainers;
import com.chartpilot.connector.config.ConnectorProperties;
import com.chartpilot.connector.model.Watermark;
import com.chartpilot.connector.index.IndexInitializer;
import com.chartpilot.connector.repository.CheckpointRepository;
import com.chartpilot.connector.repository.OrderRepository;
import com.chartpilot.connector.repository.SyncDlqRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.hc.core5.http.HttpHost;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class SyncIntegrationTest {

    private static final String INDEX = "pg-orders";

    private static DriverManagerDataSource dataSource;
    private static JdbcClient jdbc;
    private static OpenSearchClient client;
    private static ConnectorProperties properties;
    private static SyncService service;
    private static SyncStatusTracker tracker;

    @BeforeAll
    static void setUp() throws Exception {
        dataSource = new DriverManagerDataSource(
                SharedContainers.POSTGRES.getJdbcUrl(),
                SharedContainers.POSTGRES.getUsername(),
                SharedContainers.POSTGRES.getPassword());
        jdbc = JdbcClient.create(dataSource);
        client = new OpenSearchClient(ApacheHttpClient5TransportBuilder
                .builder(HttpHost.create(SharedContainers.openSearchUrl()))
                .setMapper(new JacksonJsonpMapper())
                .build());
        properties = new ConnectorProperties(INDEX, 100, 5000, 2);
        tracker = new SyncStatusTracker(Clock.systemUTC());
        service = newService(tracker);
    }

    private static SyncService newService(SyncStatusTracker statusTracker) {
        return new SyncService(
                new OrderRepository(jdbc),
                client,
                new IndexInitializer(client, properties),
                properties,
                statusTracker,
                new CheckpointRepository(jdbc),
                new SyncDlqRepository(jdbc),
                new AdvisorySyncLock(dataSource),
                new SimpleMeterRegistry());
    }

    @Test
    void initialLoadSyncsAllSeedRowsWithExplicitMapping() throws Exception {
        service.initialLoad();

        long pgCount = pgCount();
        assertThat(pgCount).isGreaterThanOrEqualTo(500);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(osCount()).isEqualTo(pgCount));

        // alias pg-orders resolves to concrete pg-orders-v1: ключ маппинга — concrete-имя
        var mappingProperties = client.indices().getMapping(request -> request.index(INDEX))
                .result().values().iterator().next().mappings().properties();
        assertThat(mappingProperties.get("status").isKeyword()).isTrue();
        assertThat(mappingProperties.get("category").isKeyword()).isTrue();
        assertThat(mappingProperties.get("customer_region").isKeyword()).isTrue();
        assertThat(mappingProperties.get("amount").isScaledFloat()).isTrue();
        assertThat(mappingProperties.get("created_at").isDate()).isTrue();
        assertThat(mappingProperties.get("updated_at").isDate()).isTrue();
        assertThat(mappingProperties.get("id").isLong()).isTrue();

        var document = client.get(request -> request.index(INDEX).id("1"), Map.class);
        assertThat(document.found()).isTrue();
        assertThat(document.source()).containsKeys("customer_region", "category", "status", "amount", "created_at");

        assertThat(tracker.snapshot().initialLoadCompleted()).isTrue();
        assertThat(tracker.snapshot().lastError()).isNull();
    }

    @Test
    void incrementalInsertIsReplicatedWithRecordIdAsDocumentId() throws Exception {
        String marker = "region-" + UUID.randomUUID();
        long id = jdbc.sql("""
                INSERT INTO orders (customer_region, category, status, amount, created_at, updated_at)
                VALUES (?, 'books', 'NEW', 12.50, now(), now())
                RETURNING id
                """)
                .param(marker)
                .query(Long.class)
                .single();

        // safety lag 2s: поллим внутри ожидания, пока строка не выйдет из окна
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
            service.poll();
            var response = client.search(request -> request
                    .index(INDEX)
                    .query(Query.of(query -> query.term(term -> term
                            .field("customer_region").value(value -> value.stringValue(marker))))),
                    Map.class);
            assertThat(response.hits().total().value()).isEqualTo(1);
            assertThat(response.hits().hits().getFirst().id()).isEqualTo(Long.toString(id));
        });
    }

    @Test
    void updateIsReplicatedViaWatermark() throws Exception {
        jdbc.sql("UPDATE orders SET status = 'CANCELLED', updated_at = now() WHERE id = 2").update();

        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
            service.poll();
            var document = client.get(request -> request.index(INDEX).id("2"), Map.class);
            assertThat(document.found()).isTrue();
            assertThat(document.source().get("status")).isEqualTo("CANCELLED");
        });
    }

    @Test
    void deleteIsReplicatedViaTombstones() throws Exception {
        String marker = "del-" + UUID.randomUUID();
        long id = jdbc.sql("""
                INSERT INTO orders (customer_region, category, status, amount, created_at, updated_at)
                VALUES (?, 'books', 'NEW', 5.00, now(), now())
                RETURNING id
                """)
                .param(marker)
                .query(Long.class)
                .single();
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
            service.poll();
            assertThat(client.get(request -> request.index(INDEX).id(Long.toString(id)), Map.class).found())
                    .isTrue();
        });

        jdbc.sql("DELETE FROM orders WHERE id = ?").param(id).update();

        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
            service.poll();
            assertThat(client.get(request -> request.index(INDEX).id(Long.toString(id)), Map.class).found())
                    .isFalse();
        });
        assertThat(jdbc.sql("SELECT count(*) FROM orders_deleted").query(Long.class).single()).isZero();
        assertThat(tracker.snapshot().deletedRecords()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void repeatedInitialLoadIsIdempotent() throws Exception {
        service.initialLoad();
        long pgCount = pgCount();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(osCount()).isEqualTo(pgCount));

        service.initialLoad();

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(osCount()).isEqualTo(pgCount));
    }

    @Test
    void restartResumesFromPersistentCheckpointWithoutFullRescan() throws Exception {
        service.initialLoad();
        long pgCount = pgCount();

        SyncStatusTracker restartedTracker = new SyncStatusTracker(Clock.systemUTC());
        SyncService restarted = newService(restartedTracker);
        restarted.initialLoad();

        assertThat(restarted.currentWatermark()).isNotEqualTo(Watermark.initial());
        assertThat(restartedTracker.snapshot().syncedRecords()).isLessThan(pgCount);
    }

    @Test
    void mvccSafetyWindowExcludesFreshRowsUntilLagPasses() throws Exception {
        OrderRepository repository = new OrderRepository(jdbc);
        String marker = "mvcc-" + UUID.randomUUID();
        long id = jdbc.sql("""
                INSERT INTO orders (customer_region, category, status, amount, created_at, updated_at)
                VALUES (?, 'books', 'NEW', 1.00, now(), now())
                RETURNING id
                """)
                .param(marker)
                .query(Long.class)
                .single();

        var fresh = repository.fetchAfter(Watermark.initial(), 1000, 2);
        assertThat(fresh).noneMatch(order -> order.id() == id);

        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
                assertThat(repository.fetchAfter(Watermark.initial(), 1000, 2))
                        .anyMatch(order -> order.id() == id));
    }

    @Test
    void advisoryLockPreventsConcurrentCyclesAcrossInstances() {
        AdvisorySyncLock first = new AdvisorySyncLock(dataSource);
        AdvisorySyncLock second = new AdvisorySyncLock(dataSource);

        assertThat(first.tryLock()).isTrue();
        try {
            assertThat(second.tryLock()).isFalse();
        } finally {
            first.unlock();
        }
        assertThat(second.tryLock()).isTrue();
        second.unlock();
    }

    private static long pgCount() {
        return jdbc.sql("SELECT count(*) FROM orders").query(Long.class).single();
    }

    private static long osCount() throws Exception {
        return client.count(request -> request.index(INDEX)).count();
    }
}