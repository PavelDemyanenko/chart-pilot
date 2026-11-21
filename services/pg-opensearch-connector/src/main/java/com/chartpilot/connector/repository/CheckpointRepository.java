package com.chartpilot.connector.repository;

import java.time.OffsetDateTime;
import java.util.Optional;

import com.chartpilot.connector.model.Watermark;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CheckpointRepository {

    private final JdbcClient jdbc;

    public CheckpointRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void ensureTable() {
        jdbc.sql("""
                CREATE TABLE IF NOT EXISTS connector_checkpoint (
                    id                   SMALLINT PRIMARY KEY DEFAULT 1,
                    watermark_updated_at TIMESTAMPTZ NOT NULL,
                    watermark_id         BIGINT NOT NULL,
                    saved_at             TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """).update();
    }

    public Optional<Watermark> load() {
        return jdbc.sql("SELECT watermark_updated_at, watermark_id FROM connector_checkpoint WHERE id = 1")
                .query((rs, rowNum) -> new Watermark(rs.getObject(1, OffsetDateTime.class), rs.getLong(2)))
                .optional();
    }

    public void save(Watermark watermark) {
        jdbc.sql("""
                INSERT INTO connector_checkpoint (id, watermark_updated_at, watermark_id, saved_at)
                VALUES (1, ?, ?, now())
                ON CONFLICT (id) DO UPDATE
                SET watermark_updated_at = EXCLUDED.watermark_updated_at,
                    watermark_id = EXCLUDED.watermark_id,
                    saved_at = now()
                """)
                .param(watermark.updatedAt())
                .param(watermark.id())
                .update();
    }
}