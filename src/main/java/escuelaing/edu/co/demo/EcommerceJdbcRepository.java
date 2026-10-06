package escuelaing.edu.co.demo;

import escuelaing.edu.co.infrastructure.capture.CaptureContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Pure JDBC implementation of the e-commerce queries.
 *
 * <p>No Spring, no ORM, no domain objects — standard {@link PreparedStatement} only.
 * Each method opens a {@link CaptureContext} so that {@code JdbcWrapper}
 * associates measured latency with the correct {@code queryId} declared
 * in {@link EcommerceQueryRegistry}.</p>
 */
public class EcommerceJdbcRepository {

    private final Connection conn;

    public EcommerceJdbcRepository(Connection conn) {
        this.conn = conn;
    }

    // -------------------------------------------------------------------------
    // Catalog queries
    // -------------------------------------------------------------------------

    /**
     * Searches active, in-stock products by category and price range, ranked by rating.
     *
     * <p>Backed by {@code idx_products_active_category}. Multi-filter on
     * category + active + stock + price range — plan change forbidden.</p>
     *
     * @return number of rows returned
     */
    public int searchProductsByCategory(String category,
                                        double minPrice,
                                        double maxPrice) throws SQLException {
        try (CaptureContext ignored = CaptureContext.begin("searchProductsByCategory");
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, name, price, stock_quantity, rating " +
                     "FROM products " +
                     "WHERE category = ? AND active = true AND stock_quantity > 0 " +
                     "AND price BETWEEN ? AND ? " +
                     "ORDER BY rating DESC, price ASC " +
                     "LIMIT 50")) {
            ps.setString(1, category);
            ps.setDouble(2, minPrice);
            ps.setDouble(3, maxPrice);
            try (ResultSet rs = ps.executeQuery()) {
                int count = 0;
                while (rs.next()) { count++; }
                return count;
            }
        }
    }

    /** Fetches full product details by primary key. */
    public void getProductDetail(int productId) throws SQLException {
        try (CaptureContext ignored = CaptureContext.begin("getProductDetail");
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, name, category, price, stock_quantity, rating, active " +
                     "FROM products " +
                     "WHERE id = ?")) {
            ps.setInt(1, productId);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); }
        }
    }

    /** Returns available stock for a product. */
    public void checkInventory(int productId) throws SQLException {
        try (CaptureContext ignored = CaptureContext.begin("checkInventory");
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT stock_quantity FROM products WHERE id = ?")) {
            ps.setInt(1, productId);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); }
        }
    }

    /**
     * Sales dashboard: revenue, order count and average price by category.
     *
     * @return number of category rows returned
     */
    public int salesDashboard() throws SQLException {
        try (CaptureContext ignored = CaptureContext.begin("salesDashboard");
                          PreparedStatement ps = conn.prepareStatement("""
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
                     ORDER BY total_revenue DESC;""")) {
            try (ResultSet rs = ps.executeQuery()) {
                int count = 0;
                while (rs.next()) { count++; }
                return count;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Write operations
    // -------------------------------------------------------------------------

    /**
     * Creates a new order for the given customer.
     *
     * @return the new order ID, or {@code -1} if the insert fails
     */
    public int createOrder(int customerId) throws SQLException {
        try (CaptureContext ignored = CaptureContext.begin("createOrder");
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO orders(customer_id, status, total_amount) " +
                     "VALUES (?, 'PENDING', 0) RETURNING id")) {
            ps.setInt(1, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    /**
     * Adjusts stock quantity by {@code delta}; no-ops if stock would go negative.
     *
     * @param delta units to add (negative for stock reduction)
     */
    public void updateInventory(int productId, int delta) throws SQLException {
        try (CaptureContext ignored = CaptureContext.begin("updateInventory");
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE products " +
                     "SET stock_quantity = stock_quantity + ? " +
                     "WHERE id = ? AND stock_quantity + ? >= 0")) {
            ps.setInt(1, delta);
            ps.setInt(2, productId);
            ps.setInt(3, delta);
            ps.executeUpdate();
        }
    }
}
