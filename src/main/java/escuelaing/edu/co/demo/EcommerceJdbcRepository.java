package escuelaing.edu.co.demo;

import escuelaing.edu.co.infrastructure.capture.CaptureContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure JDBC implementation of the e-commerce queries.
 *
 * <p>No Spring, no ORM — standard {@link PreparedStatement} only.
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
     * Returns active products in a category, ordered by rating, paginated.
     *
     * <p>Backed by {@code idx_products_active_category}. Adding an order-by-popularity
     * variant via {@code LEFT JOIN order_items GROUP BY} removes index support
     * and degrades p95 above the 300 ms SLA at production scale.</p>
     */
    public List<String> searchByCategory(String category) throws SQLException {
        try (CaptureContext ignored = CaptureContext.begin("searchProductsByCategory");
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, name, price, stock_quantity, rating " +
                     "FROM products " +
                     "WHERE active = true " +
                     "  AND category = ? " +
                     "  AND price <= (SELECT AVG(price) FROM products WHERE category = ?) " +
                     "ORDER BY rating DESC " +
                     "LIMIT 20 OFFSET 0")) {
            ps.setString(1, category);
            ps.setString(2, category);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> names = new ArrayList<>();
                while (rs.next()) names.add(rs.getString("name"));
                return names;
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
     * Returns sales revenue, order count and average price grouped by product category.
     */
    public List<String> salesDashboard() throws SQLException {
        try (CaptureContext ignored = CaptureContext.begin("salesDashboard");
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT p.category, " +
                     "       COUNT(DISTINCT o.id)             AS total_orders, " +
                     "       SUM(oi.quantity * oi.unit_price) AS total_revenue, " +
                     "       AVG(oi.unit_price)               AS avg_price " +
                     "FROM products p " +
                     "JOIN order_items oi ON oi.product_id = p.id " +
                     "JOIN orders o       ON o.id = oi.order_id " +
                     "WHERE o.status IN ('CONFIRMED','SHIPPED','DELIVERED') " +
                     "GROUP BY p.category " +
                     "ORDER BY total_revenue DESC")) {
            try (ResultSet rs = ps.executeQuery()) {
                List<String> categories = new ArrayList<>();
                while (rs.next()) categories.add(rs.getString("category"));
                return categories;
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