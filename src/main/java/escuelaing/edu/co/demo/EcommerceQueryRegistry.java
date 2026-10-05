package escuelaing.edu.co.demo;

import escuelaing.edu.co.processor.annotation.Req;
import escuelaing.edu.co.processor.annotation.SqlQuery;

/**
 * CPT-SQL query registry for the e-commerce demo.
 *
 * <p>Methods are annotated with {@link SqlQuery} and {@link Req} so that the
 * annotation processor emits {@code queries.json} at compile time (Phase 1).
 * Method bodies are empty — execution happens in {@link EcommerceJdbcRepository},
 * which links each call to its {@code queryId} via {@code CaptureContext}.</p>
 */
public class EcommerceQueryRegistry {

    // -------------------------------------------------------------------------
    // Catalog queries (read-heavy — ~80 % of normal traffic)
    // -------------------------------------------------------------------------

    /**
     * Returns active products in a category, ordered by rating, paginated.
     *
     * <p>Highest-frequency query (~60 % of traffic). {@code idx_products_active_category}
     * is critical: dropping it in a PR causes a seq scan under Zipf load
     * (hot spot on popular categories such as "electronics").</p>
     */
    @SqlQuery(queryId = "searchProductsByCategory",
              description = "Search active in-stock products by category with price range filter, ranked by rating")
    @Req(maxResponseTimeMs = 300,
         priority = Req.Priority.HIGH,
         allowPlanChange = false,
         description = "Backed by idx_products_active_category. Multi-filter search on category + active + stock + price range with rating-based ranking. Exceeding 300 ms degrades the search UX.")
    public void searchProductsByCategory(String category, double minPrice, double maxPrice) {}

    /**
     * Fetches full product details by primary key.
     *
     * <p>Second most frequent query (~20 % of traffic). Under Zipf distribution
     * the top-3 flash-sale products absorb ~80 % of these calls.</p>
     */
    @SqlQuery(queryId = "getProductDetail",
              description = "Full product details by primary key")
    @Req(maxResponseTimeMs = 50,
         priority = Req.Priority.HIGH,
         allowPlanChange = false,
         description = "SLA: 50 ms p95. PK lookup — any plan change is a degradation")
    public void getProductDetail(int productId) {}

    // -------------------------------------------------------------------------
    // Inventory queries (critical read — ~10 % of normal traffic)
    // -------------------------------------------------------------------------

    /**
     * Returns available stock for a product.
     *
     * <p>Called before checkout confirmation. During the flash-sale phase
     * (WRITE_HEAVY), hot-product stock reaches zero and this query contends
     * with concurrent {@code updateInventory} calls.</p>
     */
    @SqlQuery(queryId = "checkInventory",
              description = "Available stock for a product")
    @Req(maxResponseTimeMs = 30,
         priority = Req.Priority.HIGH,
         allowPlanChange = false,
         description = "SLA: 30 ms p95. Critical pre-checkout read — plan change forbidden")
    public void checkInventory(int productId) {}

    // -------------------------------------------------------------------------
    // Order queries (write — ~10 % of normal traffic, up to 60 % at peak)
    // -------------------------------------------------------------------------

    /**
     * Creates a new order with its line items.
     *
     * <p>Multi-table transaction: INSERT into {@code orders} + N INSERTs into
     * {@code order_items} + UPDATE on {@code products.stock_quantity}.
     * Highest contention point under the flash-sale phase (400 TPS, WRITE_HEAVY).</p>
     */
    @SqlQuery(queryId = "createOrder",
              description = "Creates an order and its line items (multi-table)")
    @Req(maxResponseTimeMs = 200,
         priority = Req.Priority.HIGH,
         allowPlanChange = true,
         description = "SLA: 200 ms p95. Plan change allowed — multi-table write")
    public void createOrder(int customerId, int productId, int quantity) {}

    /**
     * Adjusts stock quantity by {@code delta}; no-ops if stock would go negative.
     *
     * <p>Concurrent write: multiple workers may update the same product simultaneously
     * during the flash sale — maximum contention point.</p>
     */
    @SqlQuery(queryId = "updateInventory",
              description = "Adjusts product stock by delta (confirmed sale)")
    @Req(maxResponseTimeMs = 100,
         priority = Req.Priority.HIGH,
         allowPlanChange = false,
         description = "SLA: 100 ms p95. Critical concurrent write — plan change forbidden")
    public void updateInventory(int productId, int delta) {}

    // -------------------------------------------------------------------------
    // Analytics / Dashboard (low-frequency, high-cost analytical read)
    // -------------------------------------------------------------------------

    /**
     * Returns sales revenue, order count and average price grouped by category.
     *
     * <p>Base version uses simple aggregations over three tables with supporting indexes.
     * Adding window functions ({@code RANK() OVER}, {@code PERCENTILE_CONT}) or extra
     * joins degrades the plan: the planner must materialise the full result set in memory
     * before sorting — invisible at small scale, critical in production.</p>
     */
    @SqlQuery(queryId = "salesDashboard",
              description = "Sales dashboard: revenue, order count and average price by category")
    @Req(maxResponseTimeMs = 2200,
         priority = Req.Priority.HIGH,
         allowPlanChange = false,
         description = "Business tightened SLA from 2500ms to 2200ms. Adding statistical aggregations brings the query close to the new budget.")
    public void salesDashboard() {}
}
