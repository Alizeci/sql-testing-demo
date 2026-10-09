-- GitHub Copilot (agent mode, Auto -> gpt-5.6-luna), first answer, unedited.
-- Branch feature/ai-search-rating, PR #41.
SELECT id, name, price, stock_quantity, rating
FROM products
WHERE category = ? AND active = true AND stock_quantity > 0
  AND rating >= 4.0
  AND price BETWEEN ? AND ?
ORDER BY rating DESC, price ASC
LIMIT 50;
