package com.chartpilot.connector.model;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Доменная позиция watermark: в model, чтобы repository не зависел от sync. */
public record Watermark(OffsetDateTime updatedAt, long id) {

    public static Watermark initial() {
        return new Watermark(OffsetDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC), 0L);
    }
}