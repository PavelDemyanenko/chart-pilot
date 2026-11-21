-- DELETE-синхронизация (DATA-02 / ADR-0011): tombstone виден только после commit
-- удаляющей транзакции, поэтому поллинг tombstone'ов MVCC-безопасен.
CREATE TABLE IF NOT EXISTS orders_deleted (
    id         BIGINT PRIMARY KEY,
    deleted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE OR REPLACE FUNCTION orders_delete_tombstone() RETURNS trigger AS $$
BEGIN
    INSERT INTO orders_deleted (id) VALUES (OLD.id) ON CONFLICT (id) DO NOTHING;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_orders_delete ON orders;
CREATE TRIGGER trg_orders_delete
    AFTER DELETE ON orders
    FOR EACH ROW EXECUTE FUNCTION orders_delete_tombstone();