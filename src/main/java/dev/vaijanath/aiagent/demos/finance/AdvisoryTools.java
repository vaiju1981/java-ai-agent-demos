package dev.vaijanath.aiagent.demos.finance;

import static dev.vaijanath.aiagent.demos.SimpleTool.numbers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vaijanath.aiagent.demos.SimpleTool;
import dev.vaijanath.aiagent.demos.Sql;
import dev.vaijanath.aiagent.demos.SqlTool;
import dev.vaijanath.aiagent.tool.Tool;
import dev.vaijanath.aiagent.tool.ToolEffect;
import dev.vaijanath.aiagent.tool.ToolResult;
import dev.vaijanath.aiagent.tool.ToolSpec;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * A personal-finance <em>advisory</em> toolkit — what a planner actually does, over real income and
 * expense data: cash-flow and savings-rate analysis, spending trends, budget variance with a
 * year-end forecast, subscription and anomaly detection, and goal/retirement projections — plus the
 * stateless planning calculators. The advisory tools read the {@code transactions} table; rows stay
 * in SQLite. A read-only {@code sql} escape hatch covers anything bespoke.
 */
final class AdvisoryTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AdvisoryTools() {}

    @FunctionalInterface
    private interface SqlFn {
        String apply(Connection c, JsonNode args) throws Exception;
    }

    static List<Tool> toolkit(String jdbcUrl, Map<String, Double> budgets) {
        List<Tool> t = new ArrayList<>();
        t.add(new SqlTool(jdbcUrl, 50));
        t.add(new CategorizeMerchantTool());

        t.add(dbTool(jdbcUrl, "cash_flow",
                "Monthly income, expense, and net (savings) — the core of any review.",
                obj(), (c, a) -> query(c, "SELECT substr(txn_date,1,7) AS month, "
                        + "ROUND(SUM(CASE WHEN type='income' THEN amount ELSE 0 END),2) AS income, "
                        + "ROUND(SUM(CASE WHEN type='expense' THEN amount ELSE 0 END),2) AS expense, "
                        + "ROUND(SUM(CASE WHEN type='income' THEN amount ELSE -amount END),2) AS net "
                        + "FROM transactions GROUP BY month ORDER BY month", 12)));

        t.add(dbTool(jdbcUrl, "savings_rate",
                "Savings rate (net/income) overall and for the first vs second half of the year.",
                obj(), (c, a) -> {
                    double[] all = totals(c, "01", "12");
                    double[] h1 = totals(c, "01", "06");
                    double[] h2 = totals(c, "07", "12");
                    return String.format(Locale.ROOT,
                            "overall: %s saved of %s income (%s)%n  H1: %s%n  H2: %s",
                            f(all[0] - all[1]), f(all[0]), pct(all), pct(h1), pct(h2));
                }));

        t.add(dbTool(jdbcUrl, "spending_by_category",
                "Total expense per category (optionally for one month, 1-12).",
                monthSchema(), (c, a) -> {
                    String where = "WHERE type='expense'" + monthClause(a);
                    return query(c, "SELECT category, ROUND(SUM(amount),2) AS total FROM transactions "
                            + where + " GROUP BY category ORDER BY total DESC", 50);
                }));

        t.add(dbTool(jdbcUrl, "spending_trend",
                "One expense category's spend per month with month-over-month change — spots creep.",
                "{\"type\":\"object\",\"properties\":{\"category\":{\"type\":\"string\"}},"
                        + "\"required\":[\"category\"]}", AdvisoryTools::spendingTrend));

        t.add(dbTool(jdbcUrl, "budget_variance",
                "Average monthly spend vs budget per category, with an annualized year-end forecast and "
                        + "an over/under flag.", obj(), (c, a) -> budgetVariance(c, budgets)));

        t.add(dbTool(jdbcUrl, "detect_subscriptions",
                "Likely subscriptions: a merchant charging the same amount across many months "
                        + "(min_months defaults to 3). Surfaces new or forgotten recurring charges.",
                "{\"type\":\"object\",\"properties\":{\"min_months\":{\"type\":\"integer\"}}}", (c, a) -> {
                    int min = clamp(a.path("min_months").asInt(3), 2, 12);
                    return query(c, "SELECT merchant, ROUND(amount,2) AS amount, "
                            + "COUNT(DISTINCT substr(txn_date,1,7)) AS months FROM transactions "
                            + "WHERE type='expense' GROUP BY merchant, ROUND(amount,2) "
                            + "HAVING months >= " + min + " ORDER BY months DESC, amount DESC", 50);
                }));

        t.add(dbTool(jdbcUrl, "detect_anomalies",
                "Unusually large expenses: transactions far above their category's typical size "
                        + "(robust to the outlier itself). Surfaces one-off shocks.",
                obj(), AdvisoryTools::detectAnomalies));

        t.add(dbTool(jdbcUrl, "largest_expenses", "The single largest expense transactions.",
                limitSchema(), (c, a) -> {
                    int limit = clamp(a.path("limit").asInt(5), 1, 50);
                    return query(c, "SELECT txn_date, category, merchant, amount FROM transactions "
                            + "WHERE type='expense' ORDER BY amount DESC LIMIT " + limit, limit);
                }));

        t.add(dbTool(jdbcUrl, "goal_projection",
                "Months to reach a savings target at the current average monthly net, optionally plus "
                        + "an extra monthly contribution.",
                "{\"type\":\"object\",\"properties\":{\"target_amount\":{\"type\":\"number\"},"
                        + "\"extra_monthly\":{\"type\":\"number\"}},\"required\":[\"target_amount\"]}",
                AdvisoryTools::goalProjection));

        // Stateless planning calculators.
        t.add(new SimpleTool("compound_interest", "Future value with annual compounding.",
                numbers("principal", "annual_rate_pct", "years"), n -> f(n.path("principal").asDouble()
                        * Math.pow(1 + n.path("annual_rate_pct").asDouble() / 100, n.path("years").asDouble()))));
        t.add(new SimpleTool("future_savings", "Future value of fixed monthly contributions.",
                numbers("monthly", "annual_rate_pct", "years"), n -> {
                    double c = n.path("monthly").asDouble();
                    double r = n.path("annual_rate_pct").asDouble() / 100 / 12;
                    double m = n.path("years").asDouble() * 12;
                    return f(r == 0 ? c * m : c * (Math.pow(1 + r, m) - 1) / r);
                }));
        t.add(new SimpleTool("loan_monthly_payment", "Monthly payment for a fixed-rate loan.",
                numbers("principal", "annual_rate_pct", "years"), n -> {
                    double p = n.path("principal").asDouble();
                    double r = n.path("annual_rate_pct").asDouble() / 100 / 12;
                    double m = n.path("years").asDouble() * 12;
                    return f(r == 0 ? p / m : p * r / (1 - Math.pow(1 + r, -m)));
                }));
        t.add(new SimpleTool("emergency_fund_target", "Target emergency fund = monthly expenses x months.",
                numbers("monthly_expenses", "months"), n ->
                        f(n.path("monthly_expenses").asDouble() * n.path("months").asDouble())));
        return t;
    }

    private static String spendingTrend(Connection c, JsonNode a) throws Exception {
        Map<String, Double> series = new TreeMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT substr(txn_date,1,7) AS month, "
                + "ROUND(SUM(amount),2) AS total FROM transactions WHERE type='expense' AND category=? "
                + "GROUP BY month ORDER BY month")) {
            ps.setString(1, a.path("category").asText(""));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    series.put(rs.getString("month"), rs.getDouble("total"));
                }
            }
        }
        if (series.isEmpty()) {
            return "no expense data for category '" + a.path("category").asText("") + "'";
        }
        StringBuilder sb = new StringBuilder("month | spend | change%\n");
        Double prev = null;
        for (Map.Entry<String, Double> e : series.entrySet()) {
            String change = prev == null || prev == 0 ? "—"
                    : String.format(Locale.ROOT, "%+.1f%%", 100 * (e.getValue() - prev) / prev);
            sb.append(String.format(Locale.ROOT, "%s | %s | %s%n", e.getKey(), f(e.getValue()), change));
            prev = e.getValue();
        }
        return sb.toString().strip();
    }

    private static String budgetVariance(Connection c, Map<String, Double> budgets) throws Exception {
        Map<String, Double> avg = new java.util.HashMap<>();
        try (ResultSet rs = stmt(c, "SELECT category, ROUND(SUM(amount)/12.0,2) AS avg_month FROM "
                + "transactions WHERE type='expense' GROUP BY category")) {
            while (rs.next()) {
                avg.put(rs.getString("category"), rs.getDouble("avg_month"));
            }
        }
        StringBuilder sb = new StringBuilder("category | avg/mo | budget | year forecast | status\n");
        for (Map.Entry<String, Double> b : new TreeMap<>(budgets).entrySet()) {
            double a = avg.getOrDefault(b.getKey(), 0.0);
            double forecast = a * 12;
            double budgetYear = b.getValue() * 12;
            String status = a <= b.getValue()
                    ? String.format(Locale.ROOT, "under by %s/mo", f(b.getValue() - a))
                    : String.format(Locale.ROOT, "OVER by %s/mo", f(a - b.getValue()));
            sb.append(String.format(Locale.ROOT, "%s | %s | %s | %s | %s%n",
                    b.getKey(), f(a), f(b.getValue()), f(forecast) + " vs " + f(budgetYear), status));
        }
        return sb.toString().strip();
    }

    private static String detectAnomalies(Connection c, JsonNode a) throws Exception {
        // Per category, flag transactions above max($500, 4 x category median) — robust to the outlier.
        Map<String, List<double[]>> byCat = new java.util.HashMap<>(); // category -> rows of {id, amount}
        Map<String, List<Double>> amounts = new java.util.HashMap<>();
        try (ResultSet rs = stmt(c, "SELECT id, txn_date, category, merchant, amount FROM transactions "
                + "WHERE type='expense'")) {
            while (rs.next()) {
                String cat = rs.getString("category");
                amounts.computeIfAbsent(cat, k -> new ArrayList<>()).add(rs.getDouble("amount"));
            }
        }
        Map<String, Double> threshold = new java.util.HashMap<>();
        for (Map.Entry<String, List<Double>> e : amounts.entrySet()) {
            threshold.put(e.getKey(), Math.max(500.0, 4 * median(e.getValue())));
        }
        StringBuilder sb = new StringBuilder("anomalous expenses (date | category | merchant | amount | x median):\n");
        boolean any = false;
        try (ResultSet rs = stmt(c, "SELECT txn_date, category, merchant, amount FROM transactions "
                + "WHERE type='expense' ORDER BY amount DESC")) {
            while (rs.next()) {
                String cat = rs.getString("category");
                double amt = rs.getDouble("amount");
                double med = median(amounts.get(cat));
                if (amt > threshold.getOrDefault(cat, Double.MAX_VALUE)) {
                    any = true;
                    sb.append(String.format(Locale.ROOT, "  %s | %s | %s | %s | %.1fx%n",
                            rs.getString("txn_date"), cat, rs.getString("merchant"), f(amt),
                            med == 0 ? 0 : amt / med));
                }
            }
        }
        return any ? sb.toString().strip() : "no anomalous expenses found";
    }

    private static String goalProjection(Connection c, JsonNode a) throws Exception {
        double[] all = totals(c, "01", "12");
        double avgNet = (all[0] - all[1]) / 12.0;
        double monthly = avgNet + a.path("extra_monthly").asDouble(0);
        double target = a.path("target_amount").asDouble();
        if (monthly <= 0) {
            return "at the current average monthly net (" + f(avgNet) + ") the goal is not reachable "
                    + "without cutting expenses or adding contributions";
        }
        long months = (long) Math.ceil(target / monthly);
        return String.format(Locale.ROOT,
                "saving %s/mo (avg net %s + extra %s), reaching %s takes ~%d months (%.1f years)",
                f(monthly), f(avgNet), f(a.path("extra_monthly").asDouble(0)), f(target), months, months / 12.0);
    }

    // --- plumbing --------------------------------------------------------------------------------

    private static double[] totals(Connection c, String fromMonth, String toMonth) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT ROUND(SUM(CASE WHEN type='income' THEN amount ELSE 0 END),2), "
                        + "ROUND(SUM(CASE WHEN type='expense' THEN amount ELSE 0 END),2) FROM transactions "
                        + "WHERE substr(txn_date,6,2) BETWEEN ? AND ?")) {
            ps.setString(1, fromMonth);
            ps.setString(2, toMonth);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new double[] {rs.getDouble(1), rs.getDouble(2)};
            }
        }
    }

    private static double median(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    private static String pct(double[] incomeExpense) {
        double income = incomeExpense[0];
        return income == 0 ? "n/a" : String.format(Locale.ROOT, "%.1f%%", 100 * (income - incomeExpense[1]) / income);
    }

    private static String query(Connection c, String sql, int max) throws Exception {
        try (ResultSet rs = stmt(c, sql)) {
            return Sql.table(rs, max);
        }
    }

    private static ResultSet stmt(Connection c, String sql) throws Exception {
        return c.createStatement().executeQuery(sql);
    }

    private static Tool dbTool(String url, String name, String description, String schema, SqlFn fn) {
        return new Tool() {
            @Override
            public ToolSpec spec() {
                return new ToolSpec(name, description, schema, ToolEffect.READ_ONLY);
            }

            @Override
            public ToolResult invoke(String argumentsJson) {
                try (Connection c = DriverManager.getConnection(url)) {
                    return ToolResult.ok(fn.apply(c, MAPPER.readTree(argumentsJson)));
                } catch (Exception e) {
                    return ToolResult.error(name + " failed: " + e.getMessage());
                }
            }
        };
    }

    private static String monthClause(JsonNode a) {
        int month = a.path("month").asInt(0);
        return (month >= 1 && month <= 12) ? " AND substr(txn_date,6,2) = '" + String.format("%02d", month) + "'" : "";
    }

    private static String obj() {
        return "{\"type\":\"object\",\"properties\":{}}";
    }

    private static String monthSchema() {
        return "{\"type\":\"object\",\"properties\":{\"month\":{\"type\":\"integer\"}}}";
    }

    private static String limitSchema() {
        return "{\"type\":\"object\",\"properties\":{\"limit\":{\"type\":\"integer\"}}}";
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.min(Math.max(v, lo), hi);
    }

    private static String f(double d) {
        return String.format(Locale.ROOT, "%.2f", d);
    }
}
