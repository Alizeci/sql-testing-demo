package escuelaing.edu.co.demo;

import escuelaing.edu.co.processor.annotation.Req;
import escuelaing.edu.co.processor.annotation.SqlQuery;

/**
 * Performance contracts of the e-commerce demo queries.
 *
 * <p>Methods are annotated with {@link SqlQuery} and {@link Req} so that the annotation
 * processor emits {@code queries.json} at compile time. Method bodies are empty: execution
 * happens in {@link EcommerceJdbcRepository}, which links each call to its {@code queryId}
 * via {@code CaptureContext}. Traffic shares below refer to {@link EcommerceSimulator}.</p>
 */
public class EcommerceQueryRegistry {

    // Catalog queries (~80 % of simulated traffic)

    /**
     * Returns active products in a category, ordered by rating, paginated.
     *
     * <p>Highest-frequency query (~40 % of simulated traffic, tied with
     * {@link #getProductDetail}). {@code idx_products_active_category} is critical:
     * dropping it in a pull request causes a sequential scan.</p>
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
     * <p>~40 % of simulated traffic. Under Zipf access (peak profile) calls concentrate on
     * a few hot products.</p>
     */
    @SqlQuery(queryId = "getProductDetail",
              description = "Full product details by primary key")
    @Req(maxResponseTimeMs = 50,
         priority = Req.Priority.HIGH,
         allowPlanChange = false,
         description = "SLA: 50 ms p95. PK lookup — any plan change is a degradation")
    public void getProductDetail(int productId) {}

    // Inventory queries (critical read, ~8 % of simulated traffic)

    /**
     * Returns available stock for a product.
     *
     * <p>Called before checkout confirmation; on hot products it contends with
     * {@code updateInventory} writes.</p>
     */
    @SqlQuery(queryId = "checkInventory",
              description = "Available stock for a product")
    @Req(maxResponseTimeMs = 30,
         priority = Req.Priority.HIGH,
         allowPlanChange = false,
         description = "SLA: 30 ms p95. Critical pre-checkout read — plan change forbidden")
    public void checkInventory(int productId) {}

    // Order queries (writes, ~8 % of simulated traffic; the only queries replayed in
    // WRITE_HEAVY phases)

    /**
     * Creates a new order header ({@code INSERT ... RETURNING id} on {@code orders}).
     *
     * <p>In the simulator a successful order is followed by {@code updateInventory}.
     * Exercised hardest in the peak profile's flash-sale phase (400 TPS, WRITE_HEAVY).</p>
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
     * <p>Row-level write on hot products: the main contention point under concurrent
     * checkouts.</p>
     */
    @SqlQuery(queryId = "updateInventory",
              description = "Adjusts product stock by delta (confirmed sale)")
    @Req(maxResponseTimeMs = 100,
         priority = Req.Priority.HIGH,
         allowPlanChange = false,
         description = "SLA: 100 ms p95. Critical concurrent write — plan change forbidden")
    public void updateInventory(int productId, int delta) {}

    // Analytics (low-frequency, high-cost read, ~4 % of simulated traffic)

    /**
     * Returns sales revenue, order count and average price grouped by category.
     *
     * <p>Simple aggregation over three tables with supporting indexes. Adding window
     * functions ({@code RANK() OVER}, {@code PERCENTILE_CONT}) or extra joins forces the
     * full result set to be materialised before sorting: invisible at small scale, critical
     * at production volume.</p>
     */
    @SqlQuery(queryId = "salesDashboard",
              description = "Sales dashboard: revenue, order count and average price by category")
    @Req(maxResponseTimeMs = 2500,
         priority = Req.Priority.MEDIUM,
         allowPlanChange = false,
         description = "SLA: 2500 ms p95. Multi-table aggregation — window functions force a full in-memory sort at scale")
    public void salesDashboard() {}
}
