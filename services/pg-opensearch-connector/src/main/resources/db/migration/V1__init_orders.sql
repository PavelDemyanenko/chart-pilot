-- ChartPilot: таблица orders + 500 синтетических записей за последние 30 дней.
-- Guard делает миграцию идемпотентной для legacy-стендов, засеянных init-скриптом.
CREATE TABLE IF NOT EXISTS orders (
    id              BIGSERIAL PRIMARY KEY,
    customer_region VARCHAR(64)   NOT NULL,
    category        VARCHAR(64)   NOT NULL,
    status          VARCHAR(32)   NOT NULL,
    amount          NUMERIC(10,2) NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_orders_watermark ON orders (updated_at, id);

-- Служебные таблицы коннектора (DATA-01 / REL-02).
CREATE TABLE IF NOT EXISTS connector_checkpoint (
    id                   SMALLINT PRIMARY KEY DEFAULT 1,
    watermark_updated_at TIMESTAMPTZ NOT NULL,
    watermark_id         BIGINT NOT NULL,
    saved_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS sync_dlq (
    order_id   BIGINT PRIMARY KEY,
    error      TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO orders (customer_region, category, status, amount, created_at, updated_at)
SELECT
    (ARRAY['EU','NA','APAC','LATAM','MEA'])[1 + (g % 5)],
    (ARRAY['electronics','clothing','groceries','books','toys'])[1 + ((g / 5) % 5)],
    (ARRAY['NEW','PAID','SHIPPED','DELIVERED','CANCELLED'])[1 + ((g / 25) % 5)],
    round((10 + (g * 37 % 990) + (g % 100) / 100.0)::numeric, 2),
    now() - ((g % 30) || ' days')::interval - ((g * 13 % 24) || ' hours')::interval,
    now() - ((g % 30) || ' days')::interval - ((g * 13 % 24) || ' hours')::interval
FROM generate_series(1, 500) AS g
WHERE NOT EXISTS (SELECT 1 FROM orders);