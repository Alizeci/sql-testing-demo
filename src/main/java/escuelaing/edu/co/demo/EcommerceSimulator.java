package escuelaing.edu.co.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import escuelaing.edu.co.domain.model.DpRelease;
import escuelaing.edu.co.domain.model.LoadProfile;
import escuelaing.edu.co.infrastructure.analysis.QueryRegistryLoader;
import escuelaing.edu.co.infrastructure.capture.CaptureContext;
import escuelaing.edu.co.infrastructure.capture.CaptureToggle;
import escuelaing.edu.co.infrastructure.capture.DpConfig;
import escuelaing.edu.co.infrastructure.capture.FkDegreeProfiler;
import escuelaing.edu.co.infrastructure.capture.JdbcWrapper;
import escuelaing.edu.co.infrastructure.capture.LoadProfileBuilder;
import escuelaing.edu.co.infrastructure.capture.MetricsBuffer;
import escuelaing.edu.co.infrastructure.capture.SamplingFilter;
import escuelaing.edu.co.infrastructure.capture.TableProfiler;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * Simulates e-commerce traffic against the demo database (the capture environment) and
 * writes {@code load-profile.json} to the working directory.
 *
 * <p>A real application does not need this: its traffic flows through {@link JdbcWrapper}.
 * The demo has no users, so this class applies the schema, seeds data if absent, runs
 * each of the six contracted queries once (forced capture), then loops for
 * {@code SIMULATION_SECS}: per iteration one category search and one product lookup,
 * plus with probability 1/5 an inventory check, 1/10 an order with stock update, and
 * 1/10 a dashboard query, pausing 100 ms between iterations. Finally it computes the
 * differentially private table release and foreign-key concentration curves over the
 * whole capture tables.</p>
 *
 * <h3>Usage</h3>
 * <pre>
 * ./gradlew :sql-testing-demo:runSimulator
 * </pre>
 *
 * <h3>Environment variables</h3>
 * <pre>
 * DB_URL                   (default: jdbc:postgresql://localhost:5432/ecommerce_demo)
 * DB_USER                  (default: demo)
 * DB_PASSWORD              (default: demo)
 * SIMULATION_SECS          (default: 180)
 * SIMULATOR_STMT_TIMEOUT_MS (default: 8000)
 * </pre>
 */
public class EcommerceSimulator {

    private static final Logger LOG = Logger.getLogger(EcommerceSimulator.class.getName());

    private static final int SIMULATION_SECS =
            Integer.parseInt(System.getenv().getOrDefault("SIMULATION_SECS", "180"));
    private static final int THINK_TIME_MS = 100;

    private static final String[] CATEGORIES =
            {"electronics", "clothing", "books", "sports", "home"};

    public static void main(String[] args) throws Exception {
        String url  = env("DB_URL",      "jdbc:postgresql://localhost:5432/ecommerce_demo");
        String user = env("DB_USER",     "demo");
        String pass = env("DB_PASSWORD", "demo");

        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        QueryRegistryLoader registry = new QueryRegistryLoader(mapper);
        registry.load();

        CaptureToggle toggle = new CaptureToggle();
        toggle.enable();
        MetricsBuffer buffer = new MetricsBuffer();
        buffer.start();

        SamplingFilter filter = new SamplingFilter(registry);
        EcommerceSanitizationStrategy sanitization = new EcommerceSanitizationStrategy();
        JdbcWrapper wrapper = new JdbcWrapper(filter, buffer, toggle, sanitization);

        Map<String, DpRelease.ForeignKeyDegree> fkRelease = Map.of();
        DpRelease tableRelease = null;
        LOG.info("[Simulator] Connecting to " + url);
        try (Connection raw = DriverManager.getConnection(url, user, pass)) {
            applySchema(raw);
            insertSeedData(raw);

            int stmtTimeoutMs = Integer.parseInt(
                    System.getenv().getOrDefault("SIMULATOR_STMT_TIMEOUT_MS", "8000"));
            try (Statement st = raw.createStatement()) {
                st.execute("SET statement_timeout = '" + stmtTimeoutMs + "'");
            }
            LOG.info("[Simulator] statement_timeout=" + stmtTimeoutMs + "ms set.");

            Connection conn = wrapper.wrap(raw);
            EcommerceJdbcRepository repo = new EcommerceJdbcRepository(conn);
            Random rng = new Random(42);

            warmupCapture(raw, repo);

            LOG.info("[Simulator] Simulating traffic for " + SIMULATION_SECS + " s...");
            long endMs = System.currentTimeMillis() + (long) SIMULATION_SECS * 1_000;

            while (System.currentTimeMillis() < endMs) {
                int    productId  = rng.nextInt(500) + 1;
                int    customerId = rng.nextInt(200) + 1;
                String category   = CATEGORIES[rng.nextInt(CATEGORIES.length)];

                double minPrice = ThreadLocalRandom.current().nextDouble(10.0, 200.0);
                double maxPrice = minPrice + ThreadLocalRandom.current().nextDouble(50.0, 300.0);
                try { repo.searchProductsByCategory(category, minPrice, maxPrice); }
                catch (SQLException e) {
                    LOG.warning("[Simulator] searchProductsByCategory cancelled (" + e.getMessage() + ")");
                }

                try { repo.getProductDetail(productId); }
                catch (SQLException e) {
                    LOG.fine("[Simulator] getProductDetail: " + e.getMessage());
                }

                if (rng.nextInt(5) == 0) {
                    try { repo.checkInventory(productId); }
                    catch (SQLException e) {
                        LOG.fine("[Simulator] checkInventory: " + e.getMessage());
                    }
                }

                if (rng.nextInt(10) == 0) {
                    try {
                        int orderId = repo.createOrder(customerId);
                        if (orderId > 0) repo.updateInventory(productId, -1);
                    } catch (SQLException e) {
                        LOG.fine("[Simulator] createOrder/updateInventory: " + e.getMessage());
                    }
                }

                if (rng.nextInt(10) == 0) {
                    try { repo.salesDashboard(); }
                    catch (SQLException e) {
                        LOG.fine("[Simulator] salesDashboard: " + e.getMessage());
                    }
                }

                Thread.sleep(THINK_TIME_MS);
            }

            // Differentially private release over the whole tables (column statistics and
            // foreign-key concentration curves). Runs on the raw connection so these
            // statistics queries are not captured as application traffic.
            try (Statement st = raw.createStatement()) {
                st.execute("SET statement_timeout = 0");
            }
            DpConfig dpConfig = DpConfig.fromEnv();
            tableRelease = TableProfiler.profile(raw, dpConfig);
            fkRelease = FkDegreeProfiler.profile(raw, dpConfig);
            LOG.info("[Simulator] Foreign-key degree shapes released: " + fkRelease.keySet());
        }

        buffer.stop();
        LoadProfileBuilder builder = new LoadProfileBuilder(buffer)
                .withTableRelease(tableRelease)
                .withForeignKeyRelease(fkRelease);
        LoadProfile profile = builder.build();

        Path out = Path.of("load-profile.json");
        mapper.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), profile);

        LOG.info("[Simulator] load-profile.json saved — "
                + profile.getTotalSamples() + " samples, "
                + profile.getQueries().size() + " queries captured.");
    }

    private static void applySchema(Connection conn) throws Exception {
        String script;
        try (InputStream in = EcommerceSimulator.class.getClassLoader()
                .getResourceAsStream("schema-ecommerce.sql")) {
            if (in == null) throw new IllegalStateException("schema-ecommerce.sql not on classpath");
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Statement st = conn.createStatement()) {
            for (String stmt : script.split(";")) {
                String s = stmt.strip();
                if (!s.isEmpty()) {
                    try { st.execute(s); } catch (SQLException ignored) {}
                }
            }
        }
        LOG.info("[Simulator] Schema applied.");
    }

    private static final String[] ORDER_STATUSES =
            {"CONFIRMED", "CONFIRMED", "SHIPPED", "SHIPPED", "DELIVERED"};

    private static void insertSeedData(Connection conn) throws SQLException {
        try (PreparedStatement chk = conn.prepareStatement("SELECT COUNT(*) FROM orders");
             ResultSet rs = chk.executeQuery()) {
            if (rs.next() && rs.getLong(1) >= 20_000) {
                LOG.info("[Simulator] Seed data already exists — skipping.");
                return;
            }
        }

        Random rng = new Random(42);
        conn.setAutoCommit(false);

        // Customers + products
        try (PreparedStatement pc = conn.prepareStatement(
                "INSERT INTO customers(name, email, tier) VALUES(?, ?, ?) ON CONFLICT DO NOTHING");
             PreparedStatement pp = conn.prepareStatement(
                "INSERT INTO products(name, category, price, stock_quantity, rating, active) " +
                "VALUES(?, ?, ?, ?, ?, true) ON CONFLICT DO NOTHING")) {

            for (int i = 1; i <= 5000; i++) {
                pc.setString(1, "Customer " + i);
                pc.setString(2, "c" + i + "@demo.test");
                pc.setString(3, "STANDARD");
                pc.addBatch();
            }
            pc.executeBatch();

            for (int i = 1; i <= 5000; i++) {
                String cat    = CATEGORIES[rng.nextInt(CATEGORIES.length)];
                double price  = 10 + rng.nextInt(490);
                double rating = Math.round((1.0 + rng.nextDouble() * 4.0) * 100) / 100.0;
                pp.setString(1, "Product-" + i);
                pp.setString(2, cat);
                pp.setBigDecimal(3, BigDecimal.valueOf(price));
                pp.setInt(4, 50 + rng.nextInt(200));
                pp.setBigDecimal(5, BigDecimal.valueOf(rating));
                pp.addBatch();
            }
            pp.executeBatch();
            conn.commit();
        }

        // Orders: 20 000 rows, all in fulfilled statuses so salesDashboard has data
        try (PreparedStatement po = conn.prepareStatement(
                "INSERT INTO orders(customer_id, status, total_amount) VALUES(?, ?, ?)")) {
            for (int i = 0; i < 20_000; i++) {
                po.setInt(1, rng.nextInt(5000) + 1);
                po.setString(2, ORDER_STATUSES[rng.nextInt(ORDER_STATUSES.length)]);
                po.setBigDecimal(3, BigDecimal.valueOf(100 + rng.nextInt(900)));
                po.addBatch();
                if ((i + 1) % 1000 == 0) po.executeBatch();
            }
            po.executeBatch();
            conn.commit();
        }

        // Fetch generated order IDs
        List<Integer> orderIds = new ArrayList<>();
        try (PreparedStatement qs = conn.prepareStatement("SELECT id FROM orders ORDER BY id");
             ResultSet rs = qs.executeQuery()) {
            while (rs.next()) orderIds.add(rs.getInt(1));
        }

        // order_items: 10 items per order ≈ 200 000 rows — makes salesDashboard non-trivial
        try (PreparedStatement poi = conn.prepareStatement(
                "INSERT INTO order_items(order_id, product_id, quantity, unit_price) VALUES(?, ?, ?, ?)")) {
            int batch = 0;
            for (int orderId : orderIds) {
                for (int j = 0; j < 10; j++) {
                    poi.setInt(1, orderId);
                    poi.setInt(2, rng.nextInt(5000) + 1);
                    poi.setInt(3, 1 + rng.nextInt(5));
                    poi.setBigDecimal(4, BigDecimal.valueOf(10 + rng.nextInt(490)));
                    poi.addBatch();
                    if (++batch % 2000 == 0) poi.executeBatch();
                }
            }
            poi.executeBatch();
            conn.commit();
        }

        conn.setAutoCommit(true);
        LOG.info("[Simulator] Seed data inserted: 5000 customers, 5000 products, "
                + orderIds.size() + " orders, ~" + (orderIds.size() * 10) + " order_items.");
    }

    /**
     * Runs each contracted query exactly once using forced capture, guaranteeing that
     * every SQL template appears in the load profile even when the probabilistic
     * simulation window is too short to hit low-frequency queries. Write queries are
     * executed inside a transaction that is always rolled back to leave no residue.
     */
    private static void warmupCapture(Connection raw, EcommerceJdbcRepository repo) {
        int executed = 0;

        // Read-only queries — fail soft: a single failure does not abort the warmup
        try (CaptureContext ignored = CaptureContext.beginForced("searchProductsByCategory")) {
            repo.searchProductsByCategory("electronics", 20.0, 200.0);
            executed++;
        } catch (Exception e) {
            LOG.warning("[EcommerceSimulator] Warmup searchProductsByCategory: " + e.getMessage());
        }
        try (CaptureContext ignored = CaptureContext.beginForced("getProductDetail")) {
            repo.getProductDetail(1);
            executed++;
        } catch (Exception e) {
            LOG.warning("[EcommerceSimulator] Warmup getProductDetail: " + e.getMessage());
        }
        try (CaptureContext ignored = CaptureContext.beginForced("checkInventory")) {
            repo.checkInventory(1);
            executed++;
        } catch (Exception e) {
            LOG.warning("[EcommerceSimulator] Warmup checkInventory: " + e.getMessage());
        }
        try (CaptureContext ignored = CaptureContext.beginForced("salesDashboard")) {
            repo.salesDashboard();
            executed++;
        } catch (Exception e) {
            LOG.warning("[EcommerceSimulator] Warmup salesDashboard: " + e.getMessage());
        }

        // Write queries — always rolled back so the warmup leaves no residue
        try {
            raw.setAutoCommit(false);
            try (CaptureContext ignored = CaptureContext.beginForced("createOrder")) {
                repo.createOrder(1);
                executed++;
            } catch (Exception e) {
                LOG.warning("[EcommerceSimulator] Warmup createOrder: " + e.getMessage());
            }
            try (CaptureContext ignored = CaptureContext.beginForced("updateInventory")) {
                repo.updateInventory(1, 1);
                executed++;
            } catch (Exception e) {
                LOG.warning("[EcommerceSimulator] Warmup updateInventory: " + e.getMessage());
            }
        } catch (SQLException e) {
            LOG.warning("[EcommerceSimulator] Warmup transaction setup: " + e.getMessage());
        } finally {
            try { raw.rollback(); }          catch (SQLException e) { LOG.warning("[EcommerceSimulator] Warmup rollback: " + e.getMessage()); }
            try { raw.setAutoCommit(true); } catch (SQLException e) { LOG.warning("[EcommerceSimulator] Warmup autocommit: " + e.getMessage()); }
        }

        LOG.info("[EcommerceSimulator] Warmup capture complete: " + executed + " queries executed.");
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return (v != null && !v.isBlank()) ? v : def;
    }
}
