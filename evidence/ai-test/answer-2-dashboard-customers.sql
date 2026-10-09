-- GitHub Copilot (agent mode, Auto -> gpt-5.6-luna), first answer, unedited.
-- Branch feature/ai-dashboard-customers, PR #40.
WITH filtered_sales AS (
    SELECT
        p.category,
        p.id AS product_id,
        p.name AS product_name,
        o.id AS order_id,
        o.customer_id,
        oi.quantity,
        oi.unit_price
    FROM products p
    JOIN order_items oi ON oi.product_id = p.id
    JOIN orders o ON o.id = oi.order_id
    WHERE o.status IN ('CONFIRMED', 'SHIPPED', 'DELIVERED')
),
product_units AS (
    SELECT
        category,
        product_id,
        product_name,
        SUM(quantity) AS units_sold,
        ROW_NUMBER() OVER (
            PARTITION BY category
            ORDER BY SUM(quantity) DESC, product_name, product_id
        ) AS product_rank
    FROM filtered_sales
    GROUP BY category, product_id, product_name
)
SELECT
    fs.category,
    COUNT(DISTINCT fs.order_id) AS total_orders,
    SUM(fs.quantity * fs.unit_price) AS total_revenue,
    AVG(fs.unit_price) AS avg_price,
    COUNT(DISTINCT fs.customer_id) AS distinct_customers,
    pu.product_name AS best_selling_product
FROM filtered_sales fs
JOIN product_units pu
    ON pu.category = fs.category
   AND pu.product_rank = 1
GROUP BY fs.category, pu.product_name
ORDER BY total_revenue DESC;
