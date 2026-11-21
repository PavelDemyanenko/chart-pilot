package com.chartpilot.connector.repository;

import java.time.OffsetDateTime;
import java.util.List;

import com.chartpilot.connector.model.Order;
import com.chartpilot.connector.model.Watermark;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OrderRepository {

    // MVCC safety window (DATA-01): строки моложе safetyLagSeconds не читаем,
    // чтобы незакоммиченные транзакции с ранним updated_at не терялись навсегда.
    private static final String FETCH_AFTER_SQL = """
            SELECT id, customer_region, category, status, amount, created_at, updated_at
            FROM orders
            WHERE (updated_at, id) > (?, ?)
              AND updated_at <= (clock_timestamp() - make_interval(secs => ?))
            ORDER BY updated_at, id
            LIMIT ?
            """;

    // Tombstone'ы удалений (DATA-02 ): то же MVCC-окно, что для orders.
    private static final String FETCH_DELETED_SQL = """
            SELECT id
            FROM orders_deleted
            WHERE deleted_at <= (clock_timestamp() - make_interval(secs => ?))
            ORDER BY deleted_at, id
            LIMIT ?
            """;

    private final JdbcClient jdbc;

    public OrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Long> fetchDeletedIds(int safetyLagSeconds, int limit) {
        return jdbc.sql(FETCH_DELETED_SQL)
                .param(safetyLagSeconds)
                .param(limit)
                .query(Long.class)
                .list();
    }

    public void purgeDeleted(List<Long> ids) {
        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
        jdbc.sql("DELETE FROM orders_deleted WHERE id IN (" + placeholders + ")")
                .params(ids.toArray())
                .update();
    }

    public List<Order> fetchAfter(Watermark watermark, int limit, int safetyLagSeconds) {
        return jdbc.sql(FETCH_AFTER_SQL)
                .param(watermark.updatedAt())
                .param(watermark.id())
                .param(safetyLagSeconds)
                .param(limit)
                .query((rs, rowNum) -> new Order(
                        rs.getLong("id"),
                        rs.getString("customer_region"),
                        rs.getString("category"),
                        rs.getString("status"),
                        rs.getBigDecimal("amount"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class)))
                .list();
    }
}