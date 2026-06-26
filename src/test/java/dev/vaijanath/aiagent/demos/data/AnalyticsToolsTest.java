package dev.vaijanath.aiagent.demos.data;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vaijanath.aiagent.tool.Tool;
import dev.vaijanath.aiagent.tool.ToolResult;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Proves the analytics tools actually find the signal planted in {@link EcommerceData} — i.e. the
 * "deep" capability is real, not just wired. Deterministic (seeded dataset), so the directional facts
 * (positive promo elasticity, Electronics' thin margins, the West Q3 dip, Q4 seasonality, quantity
 * outliers) hold every run.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnalyticsToolsTest {

    private List<Tool> tools;

    @BeforeAll
    void setUp() throws Exception {
        tools = AnalyticsTools.toolkit(EcommerceData.createDb(1_000, 8_000));
    }

    @Test
    void profilesNumericColumns() {
        String out = run("profile_column", "{\"table\":\"order_lines\",\"column\":\"revenue\"}");
        assertTrue(out.contains("mean=") && out.contains("median=") && out.contains("stddev="),
                "numeric profile should report distribution stats: " + out);
    }

    @Test
    void discountsCorrelateWithQuantity() {
        String out = run("correlation",
                "{\"table\":\"order_lines\",\"x\":\"discount_pct\",\"y\":\"quantity\"}");
        double r = parseAfter(out, "= ");
        assertTrue(r > 0.05, "discount should positively correlate with quantity, got r=" + r);
    }

    @Test
    void electronicsHasTheThinnestMargins() {
        String out = run("group_stats",
                "{\"table\":\"order_lines\",\"group_by\":\"category\",\"op\":\"avg\",\"value\":\"margin\"}");
        String worst = lowestKey(out);
        assertTrue("Electronics".equals(worst), "Electronics should have the lowest avg margin, was: " + worst
                + "\n" + out);
    }

    @Test
    void driverAnalysisBlamesTheWestForTheQ3Dip() {
        String out = run("driver_analysis", "{\"table\":\"order_lines\",\"dimension\":\"region\","
                + "\"value\":\"revenue\",\"date_column\":\"order_date\","
                + "\"period_a_start\":\"2025-04-01\",\"period_a_end\":\"2025-06-30\","
                + "\"period_b_start\":\"2025-07-01\",\"period_b_end\":\"2025-09-30\","
                + "\"filter_column\":\"status\",\"filter_value\":\"completed\"}");
        double westDelta = rowField(out, "West", 2); // value cols: a(0) b(1) delta(2) share(3)
        assertTrue(westDelta < 0, "West revenue should fall from Q2 to Q3, delta=" + westDelta + "\n" + out);
    }

    @Test
    void revenueIsSeasonalWithAQ4Peak() {
        String out = run("time_series", "{\"table\":\"order_lines\",\"date_column\":\"order_date\","
                + "\"value\":\"revenue\",\"op\":\"sum\",\"bucket\":\"month\","
                + "\"filter_column\":\"status\",\"filter_value\":\"completed\"}");
        double jan = rowField(out, "2025-01", 0);
        double dec = rowField(out, "2025-12", 0);
        assertTrue(dec > jan, "December revenue should exceed January (Q4 peak): jan=" + jan + " dec=" + dec);
    }

    @Test
    void findsBulkQuantityOutliers() {
        String out = run("outliers",
                "{\"table\":\"order_lines\",\"column\":\"quantity\",\"id_column\":\"order_id\"}");
        double above = parseAfter(out, "above=");
        assertTrue(above > 0, "planted bulk orders should be flagged as outliers: " + out);
    }

    @Test
    void rejectsAnInvalidIdentifier() {
        ToolResult r = tool("profile_column").invoke("{\"table\":\"order_lines; DROP TABLE orders\",\"column\":\"revenue\"}");
        assertTrue(r.error(), "an injected identifier must be rejected");
    }

    // --- helpers ---------------------------------------------------------------------------------

    private String run(String name, String argsJson) {
        ToolResult r = tool(name).invoke(argsJson);
        assertFalse(r.error(), name + " errored: " + r.content());
        return r.content();
    }

    private Tool tool(String name) {
        return tools.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    /** Parses the first number appearing after {@code marker}. */
    private static double parseAfter(String text, String marker) {
        int i = text.indexOf(marker) + marker.length();
        int j = i;
        while (j < text.length() && (Character.isDigit(text.charAt(j)) || "+-.".indexOf(text.charAt(j)) >= 0)) {
            j++;
        }
        return Double.parseDouble(text.substring(i, j));
    }

    /** From a "key | v" group_stats table, returns the key with the smallest value. */
    private static String lowestKey(String table) {
        String worst = null;
        double min = Double.MAX_VALUE;
        for (String line : table.split("\n")) {
            String[] parts = line.split("\\|");
            if (parts.length < 2) {
                continue;
            }
            try {
                double v = Double.parseDouble(parts[parts.length - 1].trim());
                if (v < min) {
                    min = v;
                    worst = parts[0].trim();
                }
            } catch (NumberFormatException ignore) {
                // header row
            }
        }
        return worst;
    }

    /** From a pipe-delimited table, finds the row whose first cell equals {@code key} and returns the
     *  numeric value in data column {@code field} (0-based, counting only the value columns). */
    private static double rowField(String table, String key, int field) {
        for (String line : table.split("\n")) {
            String[] parts = line.split("\\|");
            if (parts.length >= 2 && parts[0].trim().equals(key)) {
                return Double.parseDouble(parts[field + 1].trim().replace("%", "")
                        .toLowerCase(Locale.ROOT).replace("+", ""));
            }
        }
        throw new AssertionError("no row for '" + key + "' in:\n" + table);
    }
}
