package com.chartpilot.e2e;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * E2E-сценарий 1 (acceptance scenarios): новая строка в PostgreSQL появляется в индексе pg-orders
 * в OpenSearch в течение 15 секунд, с корректными полями и _id = order.id.
 */
class DataSyncE2ETest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Test
    void insertedRowAppearsInOpenSearchWithin15Seconds() throws Exception {
        String marker = "e2e-sync-" + UUID.randomUUID();
        long id;
        try (Connection connection = DriverManager.getConnection(E2eSupport.PG_URL, E2eSupport.PG_USER, E2eSupport.PG_PASSWORD);
             Statement statement = connection.createStatement()) {
            ResultSet rs = statement.executeQuery("""
                    INSERT INTO orders (customer_region, category, status, amount, created_at, updated_at)
                    VALUES ('%s', 'books', 'NEW', 123.45, now(), now())
                    RETURNING id
                    """.formatted(marker));
            assertThat(rs.next()).isTrue();
            id = rs.getLong(1);
        }

        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            JsonNode search = postJson(E2eSupport.OPENSEARCH_URL + "/pg-orders/_search", """
                    {"query":{"term":{"customer_region":"%s"}}}
                    """.formatted(marker));
            assertThat(search.path("hits").path("total").path("value").asLong()).isEqualTo(1);
            JsonNode hit = search.path("hits").path("hits").get(0);
            assertThat(hit.path("_id").asText()).isEqualTo(Long.toString(id));
            JsonNode source = hit.path("_source");
            assertThat(source.path("category").asText()).isEqualTo("books");
            assertThat(source.path("status").asText()).isEqualTo("NEW");
            assertThat(source.path("amount").asDouble()).isEqualTo(123.45);
            assertThat(source.path("created_at").asText()).isNotBlank();
            assertThat(source.path("updated_at").asText()).isNotBlank();
        });

        JsonNode mapping = getJson(E2eSupport.OPENSEARCH_URL + "/pg-orders/_mapping");
        // alias pg-orders resolves to concrete pg-orders-v1: ключ — concrete-имя
        JsonNode properties = mapping.elements().next().path("mappings").path("properties");
        assertThat(properties.path("status").path("type").asText()).isEqualTo("keyword");
        assertThat(properties.path("amount").path("type").asText()).isEqualTo("scaled_float");
        assertThat(properties.path("created_at").path("type").asText()).isEqualTo("date");

        cleanup(id);
    }

    @Test
    void deletedRowDisappearsFromOpenSearchWithin15Seconds() throws Exception {
        String marker = "e2e-del-" + UUID.randomUUID();
        long id;
        try (Connection connection = DriverManager.getConnection(E2eSupport.PG_URL, E2eSupport.PG_USER, E2eSupport.PG_PASSWORD);
             Statement statement = connection.createStatement()) {
            ResultSet rs = statement.executeQuery("""
                    INSERT INTO orders (customer_region, category, status, amount, created_at, updated_at)
                    VALUES ('%s', 'books', 'NEW', 9.99, now(), now())
                    RETURNING id
                    """.formatted(marker));
            assertThat(rs.next()).isTrue();
            id = rs.getLong(1);
        }
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
                assertThat(docExists(id)).isTrue());

        try (Connection connection = DriverManager.getConnection(E2eSupport.PG_URL, E2eSupport.PG_USER, E2eSupport.PG_PASSWORD);
             Statement statement = connection.createStatement()) {
            assertThat(statement.executeUpdate("DELETE FROM orders WHERE id = " + id)).isEqualTo(1);
        }

        // DELETE-репликация через tombstones: документ исчезает сам
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
                assertThat(docExists(id)).isFalse());
    }

    /** OpenSearch отдаёт 404 (а не 200+found=false) на GET отсутствующего документа. */
    private static boolean docExists(long id) throws Exception {
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder(URI.create(E2eSupport.OPENSEARCH_URL + "/pg-orders/_doc/" + id)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return false;
        }
        assertThat(response.statusCode()).isEqualTo(200);
        return E2eSupport.MAPPER.readTree(response.body()).path("found").asBoolean();
    }

    /** Тестовые строки не должны попадать в демо-графики чата: удаляем через PG (DELETE-репликация). */
    private static void cleanup(long id) {
        try (Connection connection = DriverManager.getConnection(E2eSupport.PG_URL, E2eSupport.PG_USER, E2eSupport.PG_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM orders WHERE id = " + id);
        } catch (Exception e) {
            // best-effort: остаток тестовых данных не должен ронять успешный тест синхронизации
            return;
        }
        try {
            await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
                    assertThat(docExists(id)).isFalse());
        } catch (Exception e) {
            // best-effort, см. выше
        }
    }

    private static JsonNode postJson(String url, String body) throws Exception {
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return E2eSupport.MAPPER.readTree(response.body());
    }

    private static JsonNode getJson(String url) throws Exception {
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return E2eSupport.MAPPER.readTree(response.body());
    }
}