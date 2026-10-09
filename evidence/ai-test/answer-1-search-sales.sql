-- GitHub Copilot (agent mode, Auto -> gpt-5.6-luna), first answer, unedited.
-- Branch feature/ai-search-sales, PR #39.
SELECT id, name, price, stock_quantity, rating,
       COALESCE((SELECT SUM(oi.quantity)
                 FROM order_items oi
                 JOIN orders o ON o.id = oi.order_id
                 WHERE oi.product_id = p.id), 0) AS total_units_sold,
       (SELECT COUNT(*)
        FROM inventory_log il
        WHERE il.product_id = p.id) AS inventory_movement_count
FROM products p
WHERE category = ? AND active = true AND stock_quantity > 0
  AND price BETWEEN ? AND ?
ORDER BY rating DESC, price ASC
LIMIT 50
