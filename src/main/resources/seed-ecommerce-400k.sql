-- =============================================================================
-- Seed script: populate ecommerce_demo with 400,000 rows per table
--              using realistic non-uniform statistical distributions
--
-- Purpose: align the capture environment (Phase 2 / EcommerceSimulator) with
-- the volume of the PR gate mirror (Phase 3, rowsPerTable=400000), so that
-- Mirror Accuracy validation can evaluate latency equivalence between the
-- capture database and the synthetic mirror under matching volume conditions.
--
-- Distributions: statistically REALISTIC to reflect production-like patterns:
--   - Categories: Zipf-like (electronics 40%, clothing 25%, books 15%,
--                 sports 12%, home 8%)
--   - Prices: Pareto-like (80% low-cost $10-$100, 20% high-cost $100-$500)
--   - Ratings: skewed toward high values (typical e-commerce feedback pattern)
--   - Order status: realistic mix (30% CONFIRMED, 25% SHIPPED, 30% DELIVERED,
--                   10% PENDING, 5% CANCELLED)
--   - Product popularity in order_items: Zipf-like (few best-sellers dominate)
--
-- Implementation note: distributions use deterministic ranges over the
-- generate_series index (i) instead of nested random() in subqueries, because
-- PostgreSQL may evaluate subquery-scoped random() only once. Amounts within
-- each range still use random() directly at row scope.
--
-- Usage on the VM:
--   psql -U demo -d ecommerce_demo -h localhost -f seed-ecommerce-400k.sql
--
-- Estimated time: 3-6 minutes depending on VM hardware.
-- =============================================================================

-- Clean existing data
TRUNCATE inventory_log, order_items, orders, customers, products RESTART IDENTITY CASCADE;

-- =============================================================================
-- Customers: 400,000 with realistic tier distribution
-- Distribution: 5% VIP (first 20k), 15% PREMIUM (next 60k), 80% STANDARD (320k)
-- =============================================================================
INSERT INTO customers (name, email, tier)
SELECT
    'Customer ' || i,
    'customer' || i || '@demo.test',
    CASE
        WHEN i <= 20000  THEN 'VIP'
        WHEN i <= 80000  THEN 'PREMIUM'
        ELSE 'STANDARD'
    END
FROM generate_series(1, 400000) AS i
ON CONFLICT DO NOTHING;

-- =============================================================================
-- Products: 400,000 with Zipf-like category and Pareto pricing
-- Categories: electronics 40% (160k), clothing 25% (100k), books 15% (60k),
--             sports 12% (48k), home 8% (32k)
-- Prices: 80% cheap ($10-$100), 20% expensive ($100-$500), by i % 100
-- Ratings: skewed toward high (3.0-5.0, weighted toward 5)
-- Stock: skewed distribution using pow(random(), 0.5) for high-stock skew
-- Active: 95% active, 5% inactive
-- =============================================================================
INSERT INTO products (name, category, price, stock_quantity, rating, active)
SELECT
    'Product-' || i || '-' ||
        CASE
            WHEN i <= 160000 THEN 'ELE'
            WHEN i <= 260000 THEN 'CLO'
            WHEN i <= 320000 THEN 'BOO'
            WHEN i <= 368000 THEN 'SPO'
            ELSE 'HOM'
        END,
    CASE
        WHEN i <= 160000 THEN 'electronics'
        WHEN i <= 260000 THEN 'clothing'
        WHEN i <= 320000 THEN 'books'
        WHEN i <= 368000 THEN 'sports'
        ELSE 'home'
    END,
    CASE
        WHEN (i % 100) < 80 THEN round((10 + random() * 90)::numeric, 2)
        ELSE round((100 + random() * 400)::numeric, 2)
    END,
    (10 + floor(pow(random(), 0.5) * 490))::integer,
    round((3.0 + pow(random(), 0.4) * 2.0)::numeric, 2),
    CASE WHEN (i % 20) = 0 THEN false ELSE true END
FROM generate_series(1, 400000) AS i
ON CONFLICT DO NOTHING;

-- =============================================================================
-- Orders: 400,000 with realistic status distribution and skewed customer activity
-- Status: 30% CONFIRMED (first 120k), 25% SHIPPED (next 100k),
--         30% DELIVERED (next 120k), 10% PENDING (next 40k), 5% CANCELLED (20k)
-- Customer selection: pow(random(), 2) skews toward low customer IDs
--                     (VIP/PREMIUM customers order more)
-- =============================================================================
INSERT INTO orders (customer_id, status, total_amount, created_at, updated_at)
SELECT
    1 + floor(pow(random(), 2) * 400000)::integer,
    CASE
        WHEN i <= 120000 THEN 'CONFIRMED'
        WHEN i <= 220000 THEN 'SHIPPED'
        WHEN i <= 340000 THEN 'DELIVERED'
        WHEN i <= 380000 THEN 'PENDING'
        ELSE 'CANCELLED'
    END,
    round((20 + pow(random(), 0.5) * 980)::numeric, 2),
    NOW() - (random() * interval '365 days'),
    NOW() - (random() * interval '30 days')
FROM generate_series(1, 400000) AS i
ON CONFLICT DO NOTHING;

-- =============================================================================
-- Order items: 400,000 with Zipf-like product popularity
-- product_id via pow(random(), 3) heavily favors low IDs (best-sellers)
-- =============================================================================
INSERT INTO order_items (order_id, product_id, quantity, unit_price)
SELECT
    1 + floor(pow(random(), 0.5) * 400000)::integer,
    1 + floor(pow(random(), 3) * 400000)::integer,
    1 + floor(pow(random(), 2) * 5)::integer,
    round((10 + random() * 490)::numeric, 2)
FROM generate_series(1, 400000) AS i
ON CONFLICT DO NOTHING;

-- =============================================================================
-- Inventory log: 400,000 with realistic reason distribution
-- Reasons: 50% RESTOCK, 35% SALE, 10% ADJUSTMENT, 5% RETURN
-- =============================================================================
INSERT INTO inventory_log (product_id, delta, reason, created_at)
SELECT
    1 + floor(pow(random(), 3) * 400000)::integer,
    CASE
        WHEN i <= 200000 THEN (50 + floor(random() * 200))::integer
        WHEN i <= 340000 THEN -(1 + floor(random() * 20))::integer
        WHEN i <= 380000 THEN (floor(random() * 30) - 15)::integer
        ELSE (1 + floor(random() * 10))::integer
    END,
    CASE
        WHEN i <= 200000 THEN 'RESTOCK'
        WHEN i <= 340000 THEN 'SALE'
        WHEN i <= 380000 THEN 'ADJUSTMENT'
        ELSE 'RETURN'
    END,
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

-- Distribution sanity checks
SELECT 'Products by category' AS metric, category, COUNT(*) AS count
FROM products
GROUP BY category
ORDER BY count DESC;

SELECT 'Orders by status' AS metric, status, COUNT(*) AS count
FROM orders
GROUP BY status
ORDER BY count DESC;

SELECT 'Customers by tier' AS metric, tier, COUNT(*) AS count
FROM customers
GROUP BY tier
ORDER BY count DESC;
