package com.chartpilot.connector;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConnectorApplicationEndToEndTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private OpenSearchClient openSearch;

    @Autowired
    private JdbcClient jdbc;

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SharedContainers.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", SharedContainers.POSTGRES::getUsername);
        registry.add("spring.datasource.password", SharedContainers.POSTGRES::getPassword);
        registry.add("opensearch.url", SharedContainers::openSearchUrl);
        registry.add("CONNECTOR_POLL_INTERVAL_MS", () -> "1000");
    }

    @Test
    void actuatorHealthReportsUp() {
        // show-details: when_authorized (SEC-03) — агрегированный статус доступен анонимно
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            JsonNode health = getJson("/actuator/health");
            assertThat(health.path("status").asText()).isEqualTo("UP");
        });
    }

    @Test
    void syncStatusEndpointReportsCompletedInitialLoad() {
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            JsonNode status = getJson("/api/sync/status");
            assertThat(status.path("initialLoadCompleted").asBoolean()).isTrue();
            assertThat(status.path("lastError").isNull()).isTrue();
            // checkpoint общий с SyncIntegrationTest: проверяем данные в индексе, а не счётчик конкретного экземпляра
            assertThat(openSearch.count(request -> request.index("pg-orders")).count())
                    .isGreaterThanOrEqualTo(500);
        });
    }

    @Test
    void scheduledPollingReplicatesNewRowWithin15Seconds() throws Exception {
        // ждём initial load И refresh индекса, иначе before=0 и в счётчик попадут все 500 строк
        await().atMost(Duration.ofSeconds(60)).until(() ->
                openSearch.count(request -> request.index("pg-orders")).count() >= 500);
        long before = openSearch.count(request -> request.index("pg-orders")).count();
        String marker = "e2e-" + UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO orders (customer_region, category, status, amount, created_at, updated_at)
                VALUES (?, 'toys', 'NEW', 77.70, now(), now())
                """)
                .param(marker)
                .update();

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            long after = openSearch.count(request -> request.index("pg-orders")).count();
            assertThat(after).isEqualTo(before + 1);
        });
    }

    private JsonNode getJson(String path) throws Exception {
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return MAPPER.readTree(response.body());
    }
}