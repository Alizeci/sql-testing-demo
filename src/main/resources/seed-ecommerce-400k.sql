-- =============================================================================
-- Seed script: populate ecommerce_demo with 400,000 rows per table
--
-- Purpose: align the capture environment (Phase 2 / EcommerceSimulator) with
-- the volume of the PR gate mirror (Phase 3, rowsPerTable=400000), so that
-- Mirror Accuracy validation can evaluate latency equivalence between the
-- capture database and the synthetic mirror under matching volume conditions.
--
-- Distributions: simple (uniform for prices and ratings, categorical for
-- tiers/statuses, sequential for IDs). Deliberately does NOT use DPSDG to
-- preserve the rigor of the validation: dev uses simple statistical
-- distributions, mirror uses the DPSDG algorithm of the proposed system.
-- This asymmetry validates that both generators produce equivalent latencies
-- under the same volume, which is stronger evidence than using the same
-- algorithm on both sides.
--
-- Usage on the VM:
--   psql -U demo -d ecommerce_demo -h localhost -f seed-ecommerce-400k.sql
--
-- Estimated time: 2-5 minutes depending on VM hardware.
-- =============================================================================

-- Clean existing data
TRUNCATE inventory_log, order_items, orders, customers, products RESTART IDENTITY CASCADE;

-- =============================================================================
-- Customers: 400,000
-- =============================================================================
INSERT INTO customers (name, email, tier)
SELECT
    'Customer ' || i,
    'customer' || i || '@demo.test',
    CASE (i % 10)
        WHEN 0 THEN 'VIP'
        WHEN 1 THEN 'PREMIUM'
        ELSE 'STANDARD'
    END
FROM generate_series(1, 400000) AS i
ON CONFLICT DO NOTHING;

-- =============================================================================
-- Products: 400,000
-- =============================================================================
INSERT INTO products (name, category, price, stock_quantity, rating, active)
SELECT
    'Product-' || i || '-' || upper(substring(cat, 1, 3)),
    cat,
    round((10 + (random() * 490))::numeric, 2),
    (10 + floor(random() * 490))::integer,
    round((1.0 + random() * 4.0)::numeric, 2),
    true
FROM generate_series(1, 400000) AS i,
     LATERAL (
         SELECT (ARRAY['electronics','clothing','books','sports','home'])[1 + (i % 5)] AS cat
     ) AS cats
ON CONFLICT DO NOTHING;

-- =============================================================================
-- Orders: 400,000
-- =============================================================================
INSERT INTO orders (customer_id, status, total_amount, created_at, updated_at)
SELECT
    1 + (i % 400000),
    (ARRAY['PENDING','CONFIRMED','SHIPPED','DELIVERED','CANCELLED'])[1 + (i % 5)],
    round((20 + random() * 980)::numeric, 2),
    NOW() - (random() * interval '365 days'),
    NOW() - (random() * interval '30 days')
FROM generate_series(1, 400000) AS i
ON CONFLICT DO NOTHING;

-- =============================================================================
-- Order items: 400,000 (approximate 1:1 relationship with orders)
-- Note: kept at 400k to preserve the uniform rowsPerTable of the system.
-- =============================================================================
INSERT INTO order_items (order_id, product_id, quantity, unit_price)
SELECT
    1 + (i % 400000),
    1 + ((i * 7) % 400000),
    1 + floor(random() * 5)::integer,
    round((10 + random() * 490)::numeric, 2)
FROM generate_series(1, 400000) AS i
ON CONFLICT DO NOTHING;

-- =============================================================================
-- Inventory log: 400,000
-- =============================================================================
INSERT INTO inventory_log (product_id, delta, reason, created_at)
SELECT
    1 + (i % 400000),
    (50 + floor(random() * 200))::integer,
    (ARRAY['RESTOCK','SALE','ADJUSTMENT','RETURN'])[1 + (i % 4)],
    NOW() - (random() * interval '365 days')
FROM generate_series(1, 400000) AS i
ON CONFLICT DO NOTHING;

-- =============================================================================
-- Final verification
-- =============================================================================
SELECT table_name, row_count FROM (
    SELECT 'products'       AS table_name, COUNT(*) AS row_count FROM products
    UNION ALL SELECT 'customers',     COUNT(*) FROM customers
    UNION ALL SELECT 'orders',        COUNT(*) FROM orders
    UNION ALL SELECT 'order_items',   COUNT(*) FROM order_items
    UNION ALL SELECT 'inventory_log', COUNT(*) FROM inventory_log
) t ORDER BY table_name;
