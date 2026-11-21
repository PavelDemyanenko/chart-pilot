package com.chartpilot.connector.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;

public record Order(
        long id,
        String customerRegion,
        String category,
        String status,
        BigDecimal amount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public Order {
        Objects.requireNonNull(customerRegion, "customerRegion");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public Map<String, Object> toDocument() {
        return Map.of(
                "id", id,
                "customer_region", customerRegion,
                "category", category,
                "status", status,
                "amount", amount.doubleValue(),
                "created_at", DateTimeFormatter.ISO_INSTANT.format(createdAt.toInstant()),
                "updated_at", DateTimeFormatter.ISO_INSTANT.format(updatedAt.toInstant()));
    }
}