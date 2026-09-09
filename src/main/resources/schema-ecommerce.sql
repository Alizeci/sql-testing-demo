-- =============================================================================
-- Demo application schema: e-commerce with flash-sale simulation
--
-- Purpose: demonstrate the continuous load-testing system (Phase 3)
-- over a domain that any development team recognizes.
--
-- The demo application instruments its repositories with @SqlQuery + @Req (Phase 1),
-- captures real traffic with JdbcWrapper (Phase 2), and uses this schema in the mirror
-- database to detect SQL regressions before each merge (Phase 3 + 4).
--
-- Load scenario: TestProfile "peak" — flash sale with Zipf distribution.
-- Matches the "Peak" challenge from BenchPress (Van Aken et al., SIGMOD 2015).
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Product catalog
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS products (
    id             SERIAL PRIMARY KEY,
    name           VARCHAR(120)   NOT NULL,
    category       VARCHAR(60)    NOT NULL,
    price          NUMERIC(10,2)  NOT NULL CHECK (price > 0),
    stock_quantity INTEGER        NOT NULL DEFAULT 0 CHECK (stock_quantity >= 0),
    rating         NUMERIC(3,2)            CHECK (rating BETWEEN 0 AND 5),
    active         BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMP      NOT NULL DEFAULT NOW()
);

-- Category search index — key to detecting PLAN_CHANGED
-- if someone removes or changes it in a PR
CREATE INDEX IF NOT EXISTS idx_products_category ON products (category);
-- TEST 3 (Scenario A): index commented out to simulate
-- removal through a schema change in a PR.
-- CREATE INDEX IF NOT EXISTS idx_products_active_category
--     ON products (active, category);

-- -----------------------------------------------------------------------------
-- Customers
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS customers (
    id         SERIAL PRIMARY KEY,
    name       VARCHAR(120)  NOT NULL,
    email      VARCHAR(200)  NOT NULL UNIQUE,
    tier       VARCHAR(20)   NOT NULL DEFAULT 'STANDARD'
                             CHECK (tier IN ('STANDARD', 'PREMIUM', 'VIP')),
    created_at TIMESTAMP     NOT NULL DEFAULT NOW()
);

-- -----------------------------------------------------------------------------
-- Orders
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS orders (
    id           SERIAL PRIMARY KEY,
    customer_id  INTEGER       NOT NULL REFERENCES customers(id),
    status       VARCHAR(30)   NOT NULL DEFAULT 'PENDING'
                               CHECK (status IN ('PENDING','CONFIRMED','SHIPPED','DELIVERED','CANCELLED')),
    total_amount NUMERIC(12,2) NOT NULL DEFAULT 0,
    created_at   TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_orders_customer ON orders (customer_id);
CREATE INDEX IF NOT EXISTS idx_orders_status   ON orders (status);
CREATE INDEX IF NOT EXISTS idx_orders_created  ON orders (created_at DESC);

-- -----------------------------------------------------------------------------
-- Order lines (items inside each order)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS order_items (
    id          SERIAL PRIMARY KEY,
    order_id    INTEGER       NOT NULL REFERENCES orders(id),
    product_id  INTEGER       NOT NULL REFERENCES products(id),
    quantity    INTEGER       NOT NULL CHECK (quantity > 0),
    unit_price  NUMERIC(10,2) NOT NULL CHECK (unit_price > 0)
);

CREATE INDEX IF NOT EXISTS idx_order_items_order   ON order_items (order_id);
CREATE INDEX IF NOT EXISTS idx_order_items_product ON order_items (product_id);

-- -----------------------------------------------------------------------------
-- Inventory log (stock change traceability)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS inventory_log (
    id          SERIAL PRIMARY KEY,
    product_id  INTEGER       NOT NULL REFERENCES products(id),
    delta       INTEGER       NOT NULL,   -- positivo: entrada, negativo: salida
    reason      VARCHAR(60)   NOT NULL,   -- 'SALE', 'RESTOCK', 'ADJUSTMENT'
    created_at  TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_inventory_log_product ON inventory_log (product_id, created_at DESC);
