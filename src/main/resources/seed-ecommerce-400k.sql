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
--   - Categories: Zipf-like (electronics dominant, home least frequent)
--   - Prices: Pareto-like (80% low-cost, 20% high-cost items)
--   - Ratings: skewed toward high values (typical e-commerce feedback pattern)
--   - Order status: realistic mix (most orders confirmed/shipped/delivered)
--   - Product popularity in order_items: Zipf-like (few best-sellers dominate)
--
-- These distributions produce statistically comparable behavior to DPSDG
-- (which also generates data with variability), enabling meaningful latency
-- equivalence between capture DB and synthetic mirror.
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
-- Distribution: 5% VIP, 15% PREMIUM, 80% STANDARD
-- =============================================================================
INSERT INTO customers (name, email, tier)
SELECT
    'Customer ' || i,
    'customer' || i || '@demo.test',
    CASE
        WHEN r < 0.05 THEN 'VIP'
        WHEN r < 0.20 THEN 'PREMIUM'
        ELSE 'STANDARD'
    END
FROM generate_series(1, 400000) AS i,
     LATERAL (SELECT random() AS r) AS rnd
ON CONFLICT DO NOTHING;

-- =============================================================================
-- Products: 400,000 with Zipf-like category distribution and Pareto pricing
-- Categories: electronics 40%, clothing 25%, books 15%, sports 12%, home 8%
-- Prices: 80% cheap ($10-$100), 20% expensive ($100-$500)
-- Ratings: skewed toward high (3.0-5.0 range, weighted toward 5)
-- Stock: skewed distribution (most items well-stocked, some scarce)
-- =============================================================================
INSERT INTO products (name, category, price, stock_quantity, rating, active)
SELECT
    'Product-' || i || '-' || upper(substring(cat, 1, 3)),
    cat,
    price_val,
    stock_val,
    rating_val,
    CASE WHEN random() < 0.95 THEN true ELSE false END
FROM generate_series(1, 400000) AS i,
     LATERAL (
         SELECT
             CASE
                 WHEN r_cat < 0.40 THEN 'electronics'
                 WHEN r_cat < 0.65 THEN 'clothing'
                 WHEN r_cat < 0.80 THEN 'books'
                 WHEN r_cat < 0.92 THEN 'sports'
                 ELSE 'home'
             END AS cat,
             CASE
                 WHEN r_price < 0.80 THEN round((10 + random() * 90)::numeric, 2)
                 ELSE round((100 + random() * 400)::numeric, 2)
             END AS price_val,
             (10 + floor(pow(random(), 0.5) * 490))::integer AS stock_val,
             round((3.0 + pow(random(), 0.4) * 2.0)::numeric, 2) AS rating_val
         FROM (
             SELECT random() AS r_cat, random() AS r_price
         ) x
     ) AS distr
ON CONFLICT DO NOTHING;

-- =============================================================================
-- Orders: 400,000 with realistic status distribution
-- Customers with skewed activity (some customers order more)
-- Status: 30% CONFIRMED, 25% SHIPPED, 30% DELIVERED, 10% PENDING, 5% CANCELLED
-- =============================================================================
INSERT INTO orders (customer_id, status, total_amount, created_at, updated_at)
SELECT
    1 + floor(pow(random(), 2) * 400000)::integer,
    CASE
        WHEN r_stat < 0.30 THEN 'CONFIRMED'
        WHEN r_stat < 0.55 THEN 'SHIPPED'
        WHEN r_stat < 0.85 THEN 'DELIVERED'
        WHEN r_stat < 0.95 THEN 'PENDING'
        ELSE 'CANCELLED'
    END,
    round((20 + pow(random(), 0.5) * 980)::numeric, 2),
    NOW() - (random() * interval '365 days'),
    NOW() - (random() * interval '30 days')
FROM generate_series(1, 400000) AS i,
     LATERAL (SELECT random() AS r_stat) AS rnd
ON CONFLICT DO NOTHING;

-- =============================================================================
-- Order items: 400,000 with Zipf-like product popularity
-- Best-seller products (low IDs) appear more frequently than tail products
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
        WHEN r_reason < 0.50 THEN (50 + floor(random() * 200))::integer
        WHEN r_reason < 0.85 THEN -(1 + floor(random() * 20))::integer
        WHEN r_reason < 0.95 THEN (floor(random() * 30) - 15)::integer
        ELSE (1 + floor(random() * 10))::integer
    END,
    CASE
        WHEN r_reason < 0.50 THEN 'RESTOCK'
        WHEN r_reason < 0.85 THEN 'SALE'
        WHEN r_reason < 0.95 THEN 'ADJUSTMENT'
        ELSE 'RETURN'
    END,
    NOW() - (random() * interval '365 days')
FROM generate_series(1, 400000) AS i,
     LATERAL (SELECT random() AS r_reason) AS rnd
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

-- Distribution sanity check
SELECT 'Products by category' AS metric, category, COUNT(*) AS count
FROM products
GROUP BY category
ORDER BY count DESC;

SELECT 'Orders by status' AS metric, status, COUNT(*) AS count
FROM orders
GROUP BY status
ORDER BY count DESC;
