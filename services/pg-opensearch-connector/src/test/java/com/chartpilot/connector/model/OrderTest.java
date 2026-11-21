package com.chartpilot.connector.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderTest {

    @Test
    void toDocumentProducesAggregationFriendlyTypes() {
        OffsetDateTime created = OffsetDateTime.of(2026, 11, 1, 10, 15, 30, 0, ZoneOffset.UTC);
        OffsetDateTime updated = created.plusHours(2);
        Order order = new Order(42L, "EU", "electronics", "PAID", new BigDecimal("199.99"), created, updated);

        Map<String, Object> document = order.toDocument();

        assertThat(document)
                .containsEntry("id", 42L)
                .containsEntry("customer_region", "EU")
                .containsEntry("category", "electronics")
                .containsEntry("status", "PAID");
        assertThat(document.get("amount")).isEqualTo(199.99d);
        assertThat(document.get("created_at")).isEqualTo("2026-11-01T10:15:30Z");
        assertThat(document.get("updated_at")).isEqualTo("2026-11-01T12:15:30Z");
        assertThat(Instant.parse((String) document.get("created_at"))).isNotNull();
    }
}