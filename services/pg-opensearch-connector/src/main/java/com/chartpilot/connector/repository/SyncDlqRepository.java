package com.chartpilot.connector.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SyncDlqRepository {

    private final JdbcClient jdbc;

    public SyncDlqRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void ensureTable() {
        jdbc.sql("""
                CREATE TABLE IF NOT EXISTS sync_dlq (
                    order_id   BIGINT PRIMARY KEY,
                    error      TEXT NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """).update();
    }

    public void record(long orderId, String error) {
        jdbc.sql("""
                INSERT INTO sync_dlq (order_id, error)
                VALUES (?, ?)
                ON CONFLICT (order_id) DO UPDATE SET error = EXCLUDED.error, created_at = now()
                """)
                .param(orderId)
                .param(error)
                .update();
    }
}