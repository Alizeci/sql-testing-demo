package escuelaing.edu.co.demo;

import escuelaing.edu.co.infrastructure.capture.SanitizationStrategy;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Sanitization strategy for the e-commerce demo schema.
 *
 * <p>Keeps only whitelisted, non-identifying business attributes (column names are
 * lower-cased) and drops every other column, so no value that could identify a person
 * reaches the differentially private release (data-minimisation principle, e.g. GDPR).</p>
 *
 * <h3>Allowed columns</h3>
 * {@code price}, {@code category}, {@code quantity}, {@code stock_quantity},
 * {@code status}, {@code payment_method}, {@code discount_percent},
 * {@code rating}, {@code total_amount}.
 */
@Component
public class EcommerceSanitizationStrategy implements SanitizationStrategy {

    static final Set<String> WHITELIST = Set.of(
            "price",
            "category",
            "quantity",
            "stock_quantity",
            "status",
            "payment_method",
            "discount_percent",
            "rating",
            "total_amount"
    );

    @Override
    public Map<String, Object> sanitize(ResultSet rs) throws SQLException {
        Map<String, Object> result = new HashMap<>();
        ResultSetMetaData meta = rs.getMetaData();
        int cols = meta.getColumnCount();

        for (int i = 1; i <= cols; i++) {
            String colName = meta.getColumnName(i).toLowerCase();
            if (WHITELIST.contains(colName)) {
                result.put(colName, rs.getObject(i));
            }
        }

        return result;
    }

    @Override
    public Map<String, Object> sanitize(Map<String, Object> rawRow) {
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : rawRow.entrySet()) {
            if (WHITELIST.contains(entry.getKey().toLowerCase())) {
                result.put(entry.getKey().toLowerCase(), entry.getValue());
            }
        }
        return result;
    }
}
