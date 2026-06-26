package dev.vaijanath.aiagent.demos.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A real exploratory-data-analysis toolkit over a SQLite warehouse — the building blocks an analyst
 * actually uses, not just {@code GROUP BY}: schema discovery, column profiling, histograms, IQR
 * outliers, correlation, multi-dimension segmentation, time-series with period-over-period change,
 * and driver (contribution-to-change) analysis. Each tool returns a compact, model-readable summary;
 * the rows stay in the database. A read-only {@code sql} escape hatch covers anything bespoke.
 *
 * <p>Optional categorical filters are bound as parameters (never string-concatenated), and every
 * table/column name is validated as a bare identifier — so a tool argument can't smuggle SQL.
 */
final class AnalyticsTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> AGGREGATES = Set.of("count", "sum", "avg", "min", "max");

    private AnalyticsTools() {}

    @FunctionalInterface
    private interface SqlFn {
        String apply(Connection c, JsonNode args) throws Exception;
    }

    static List<Tool> toolkit(String jdbcUrl) {
        List<Tool> tools = new ArrayList<>();
        tools.add(new SqlTool(jdbcUrl, 50));

        tools.add(tool(jdbcUrl, "list_tables",
                "List the tables and views in the database.",
                obj(),
                (c, a) -> Sql.firstColumn(query(c, "SELECT name FROM sqlite_master "
                        + "WHERE type IN ('table','view') AND name NOT LIKE 'sqlite_%' ORDER BY name"))));

        tools.add(tool(jdbcUrl, "describe_table",
                "List a table's columns and types. Start here to learn the schema.",
                obj("\"table\":{\"type\":\"string\"}", "table"),
                (c, a) -> {
                    String table = Sql.ident(a.path("table").asText());
                    try (ResultSet rs = query(c, "PRAGMA table_info(" + table + ")")) {
                        StringBuilder sb = new StringBuilder();
                        while (rs.next()) {
                            sb.append(rs.getString("name")).append(' ')
                                    .append(emptyToAny(rs.getString("type"))).append('\n');
                        }
                        return sb.toString().strip();
                    }
                }));

        tools.add(tool(jdbcUrl, "sample_rows",
                "Return a few sample rows from a table to see what the data looks like.",
                obj("\"table\":{\"type\":\"string\"},\"limit\":{\"type\":\"integer\"}", "table"),
                (c, a) -> {
                    String table = Sql.ident(a.path("table").asText());
                    int limit = clamp(a.path("limit").asInt(5), 1, 20);
                    try (ResultSet rs = query(c, "SELECT * FROM " + table + " LIMIT " + limit)) {
                        return Sql.table(rs, limit);
                    }
                }));

        tools.add(tool(jdbcUrl, "profile_column",
                "Profile one column (EDA): for a numeric column, count/nulls/min/max/mean/median/"
                        + "stddev/p25/p75; for a categorical one, distinct count and top values. "
                        + "Optionally filter to a category (e.g. status=completed).",
                obj("\"table\":{\"type\":\"string\"},\"column\":{\"type\":\"string\"}," + FILTER, "table", "column"),
                AnalyticsTools::profileColumn));

        tools.add(tool(jdbcUrl, "histogram",
                "Bucket a numeric column into a histogram (default 10 bins) to see its distribution.",
                obj("\"table\":{\"type\":\"string\"},\"column\":{\"type\":\"string\"},"
                        + "\"bins\":{\"type\":\"integer\"}," + FILTER, "table", "column"),
                AnalyticsTools::histogram));

        tools.add(tool(jdbcUrl, "outliers",
                "Find outliers in a numeric column using Tukey's 1.5*IQR fences; reports the bounds, "
                        + "how many fall outside, and the most extreme examples (with an id column if given).",
                obj("\"table\":{\"type\":\"string\"},\"column\":{\"type\":\"string\"},"
                        + "\"id_column\":{\"type\":\"string\"}," + FILTER, "table", "column"),
                AnalyticsTools::outliers));

        tools.add(tool(jdbcUrl, "correlation",
                "Pearson correlation between two numeric columns over the rows where both are present.",
                obj("\"table\":{\"type\":\"string\"},\"x\":{\"type\":\"string\"},\"y\":{\"type\":\"string\"}," + FILTER,
                        "table", "x", "y"),
                AnalyticsTools::correlation));

        tools.add(tool(jdbcUrl, "group_stats",
                "Segment: group by one or two dimensions and aggregate a value (op: count/sum/avg/"
                        + "min/max). Returns up to 50 groups, highest first. Use two dimensions for a "
                        + "hierarchical breakdown (e.g. category then subcategory).",
                obj("\"table\":{\"type\":\"string\"},\"group_by\":{\"type\":\"string\"},"
                        + "\"group_by2\":{\"type\":\"string\"},\"op\":{\"type\":\"string\","
                        + "\"enum\":[\"count\",\"sum\",\"avg\",\"min\",\"max\"]},"
                        + "\"value\":{\"type\":\"string\"}," + FILTER, "table", "group_by", "op"),
                AnalyticsTools::groupStats));

        tools.add(tool(jdbcUrl, "time_series",
                "Aggregate a value over time buckets (month or quarter) to see trend and seasonality, "
                        + "with period-over-period % change. Needs a date column (ISO yyyy-mm-dd).",
                obj("\"table\":{\"type\":\"string\"},\"date_column\":{\"type\":\"string\"},"
                        + "\"value\":{\"type\":\"string\"},\"op\":{\"type\":\"string\","
                        + "\"enum\":[\"count\",\"sum\",\"avg\"]},"
                        + "\"bucket\":{\"type\":\"string\",\"enum\":[\"month\",\"quarter\"]}," + FILTER,
                        "table", "date_column", "op"),
                AnalyticsTools::timeSeries));

        tools.add(tool(jdbcUrl, "driver_analysis",
                "Explain a change: compare a summed value between two date periods, broken down by a "
                        + "dimension, ranked by each segment's contribution to the total change. "
                        + "Periods are ISO date ranges, e.g. period_a_start=2025-04-01.",
                obj("\"table\":{\"type\":\"string\"},\"dimension\":{\"type\":\"string\"},"
                        + "\"value\":{\"type\":\"string\"},\"date_column\":{\"type\":\"string\"},"
                        + "\"period_a_start\":{\"type\":\"string\"},\"period_a_end\":{\"type\":\"string\"},"
                        + "\"period_b_start\":{\"type\":\"string\"},\"period_b_end\":{\"type\":\"string\"}," + FILTER,
                        "table", "dimension", "value", "date_column",
                        "period_a_start", "period_a_end", "period_b_start", "period_b_end"),
                AnalyticsTools::driverAnalysis));

        return tools;
    }

    // ---- EDA -------------------------------------------------------------------------------------

    private static String profileColumn(Connection c, JsonNode a) throws Exception {
        String table = Sql.ident(a.path("table").asText());
        String col = Sql.ident(a.path("column").asText());
        long total;
        long nonNull;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*), COUNT(" + col + ") FROM " + table + whereFilter(a))) {
            bindFilter(ps, 1, a);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                total = rs.getLong(1);
                nonNull = rs.getLong(2);
            }
        }
        double[] nums = numericColumn(c, table, col, a);
        boolean numeric = nonNull > 0 && nums.length >= 0.9 * nonNull;
        if (numeric && nums.length > 0) {
            return String.format(Locale.ROOT,
                    "%s.%s (numeric): count=%d nulls=%d%n  min=%s  p25=%s  median=%s  mean=%s  p75=%s  "
                            + "max=%s  stddev=%s",
                    table, col, nonNull, total - nonNull,
                    Stats.round(min(nums), 2), Stats.round(Stats.percentile(nums, 25), 2),
                    Stats.round(Stats.median(nums), 2), Stats.round(Stats.mean(nums), 2),
                    Stats.round(Stats.percentile(nums, 75), 2), Stats.round(max(nums), 2),
                    Stats.round(Stats.stddev(nums), 2));
        }
        long distinct;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(DISTINCT " + col + ") FROM " + table + whereFilter(a))) {
            bindFilter(ps, 1, a);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                distinct = rs.getLong(1);
            }
        }
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
                "%s.%s (categorical): count=%d nulls=%d distinct=%d%n  top values:%n",
                table, col, nonNull, total - nonNull, distinct));
        try (PreparedStatement ps = c.prepareStatement("SELECT " + col + " AS v, COUNT(*) AS n FROM "
                + table + whereFilter(a) + " GROUP BY " + col + " ORDER BY n DESC LIMIT 10")) {
            bindFilter(ps, 1, a);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sb.append("    ").append(rs.getString("v")).append(": ").append(rs.getLong("n")).append('\n');
                }
            }
        }
        return sb.toString().strip();
    }

    private static String histogram(Connection c, JsonNode a) throws Exception {
        String table = Sql.ident(a.path("table").asText());
        String col = Sql.ident(a.path("column").asText());
        int bins = clamp(a.path("bins").asInt(10), 2, 30);
        double[] xs = numericColumn(c, table, col, a);
        if (xs.length == 0) {
            return "no numeric values in " + table + "." + col;
        }
        double lo = min(xs);
        double hi = max(xs);
        if (lo == hi) {
            return col + ": all " + xs.length + " values = " + Stats.round(lo, 2);
        }
        double width = (hi - lo) / bins;
        int[] counts = new int[bins];
        for (double x : xs) {
            int b = (int) ((x - lo) / width);
            counts[Math.min(b, bins - 1)]++;
        }
        int max = 1;
        for (int n : counts) {
            max = Math.max(max, n);
        }
        StringBuilder sb = new StringBuilder(col + " histogram (" + xs.length + " values):\n");
        for (int i = 0; i < bins; i++) {
            double from = lo + i * width;
            int bar = (int) Math.round(30.0 * counts[i] / max);
            sb.append(String.format(Locale.ROOT, "  %10.2f .. %10.2f | %-30s %d%n",
                    from, from + width, "#".repeat(bar), counts[i]));
        }
        return sb.toString().strip();
    }

    private static String outliers(Connection c, JsonNode a) throws Exception {
        String table = Sql.ident(a.path("table").asText());
        String col = Sql.ident(a.path("column").asText());
        double[] xs = numericColumn(c, table, col, a);
        if (xs.length < 4) {
            return "not enough numeric values to assess outliers in " + table + "." + col;
        }
        double[] fences = Stats.iqrFences(xs);
        long below = 0;
        long above = 0;
        for (double x : xs) {
            if (x < fences[0]) {
                below++;
            } else if (x > fences[1]) {
                above++;
            }
        }
        String idCol = a.hasNonNull("id_column") ? Sql.ident(a.path("id_column").asText()) : null;
        String select = (idCol != null ? idCol + " AS id, " : "") + col + " AS v";
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
                "%s.%s outliers (Tukey 1.5*IQR): fences=[%s, %s]  below=%d  above=%d  of %d rows%n  "
                        + "most extreme:%n",
                table, col, Stats.round(fences[0], 2), Stats.round(fences[1], 2), below, above, xs.length));
        try (PreparedStatement ps = c.prepareStatement("SELECT " + select + " FROM " + table
                + " WHERE " + col + " > ?" + andFilter(a) + " ORDER BY " + col + " DESC LIMIT 5")) {
            ps.setDouble(1, fences[1]);
            bindFilter(ps, 2, a);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sb.append("    ").append(idCol != null ? rs.getString("id") + ": " : "")
                            .append(rs.getString("v")).append('\n');
                }
            }
        }
        return sb.toString().strip();
    }

    private static String correlation(Connection c, JsonNode a) throws Exception {
        String table = Sql.ident(a.path("table").asText());
        String x = Sql.ident(a.path("x").asText());
        String y = Sql.ident(a.path("y").asText());
        List<double[]> pairs = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT " + x + ", " + y + " FROM " + table
                + " WHERE " + x + " IS NOT NULL AND " + y + " IS NOT NULL" + andFilter(a))) {
            bindFilter(ps, 1, a);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        pairs.add(new double[] {Double.parseDouble(rs.getString(1)), Double.parseDouble(rs.getString(2))});
                    } catch (NumberFormatException | NullPointerException ignore) {
                        // skip non-numeric rows
                    }
                }
            }
        }
        double[] xs = new double[pairs.size()];
        double[] ys = new double[pairs.size()];
        for (int i = 0; i < pairs.size(); i++) {
            xs[i] = pairs.get(i)[0];
            ys[i] = pairs.get(i)[1];
        }
        double r = Stats.pearson(xs, ys);
        return String.format(Locale.ROOT, "Pearson r(%s, %s) = %s over %d rows — %s",
                x, y, Stats.round(r, 3), pairs.size(), interpret(r));
    }

    // ---- aggregation ----------------------------------------------------------------------------

    private static String groupStats(Connection c, JsonNode a) throws Exception {
        String table = Sql.ident(a.path("table").asText());
        String g1 = Sql.ident(a.path("group_by").asText());
        String op = a.path("op").asText("").toLowerCase(Locale.ROOT);
        if (!AGGREGATES.contains(op)) {
            return "unsupported op '" + op + "' (use count/sum/avg/min/max)";
        }
        String g2 = a.hasNonNull("group_by2") ? Sql.ident(a.path("group_by2").asText()) : null;
        String expr = op.equals("count") ? "COUNT(*)"
                : op.toUpperCase(Locale.ROOT) + "(" + Sql.ident(a.path("value").asText()) + ")";
        String dims = g1 + (g2 != null ? ", " + g2 : "");
        String sql = "SELECT " + dims + ", ROUND(" + expr + ", 2) AS result FROM " + table
                + whereFilter(a) + " GROUP BY " + dims + " ORDER BY result DESC LIMIT 50";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bindFilter(ps, 1, a);
            try (ResultSet rs = ps.executeQuery()) {
                return Sql.table(rs, 50);
            }
        }
    }

    private static String timeSeries(Connection c, JsonNode a) throws Exception {
        String table = Sql.ident(a.path("table").asText());
        String dateCol = Sql.ident(a.path("date_column").asText());
        String op = a.path("op").asText("").toLowerCase(Locale.ROOT);
        if (!Set.of("count", "sum", "avg").contains(op)) {
            return "unsupported op '" + op + "' (use count/sum/avg)";
        }
        boolean quarter = "quarter".equalsIgnoreCase(a.path("bucket").asText("month"));
        // month bucket = yyyy-mm; quarter bucket = yyyy-Qn derived from the month.
        String bucketExpr = "substr(" + dateCol + ",1,7)";
        String expr = op.equals("count") ? "COUNT(*)"
                : op.toUpperCase(Locale.ROOT) + "(" + Sql.ident(a.path("value").asText()) + ")";
        Map<String, Double> series = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT " + bucketExpr + " AS bucket, ROUND("
                + expr + ", 2) AS result FROM " + table + whereFilter(a)
                + " GROUP BY bucket ORDER BY bucket")) {
            bindFilter(ps, 1, a);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    series.merge(quarter ? toQuarter(rs.getString("bucket")) : rs.getString("bucket"),
                            rs.getDouble("result"), Double::sum);
                }
            }
        }
        StringBuilder sb = new StringBuilder("period | " + op + " | change%\n");
        Double prev = null;
        for (Map.Entry<String, Double> e : series.entrySet()) {
            String change = prev == null || prev == 0 ? "—"
                    : String.format(Locale.ROOT, "%+.1f%%", 100.0 * (e.getValue() - prev) / prev);
            sb.append(String.format(Locale.ROOT, "%s | %s | %s%n",
                    e.getKey(), Stats.round(e.getValue(), 2), change));
            prev = e.getValue();
        }
        return sb.toString().strip();
    }

    private static String driverAnalysis(Connection c, JsonNode a) throws Exception {
        String table = Sql.ident(a.path("table").asText());
        String dim = Sql.ident(a.path("dimension").asText());
        String value = Sql.ident(a.path("value").asText());
        String dateCol = Sql.ident(a.path("date_column").asText());
        Map<String, Double> aVals = periodSums(c, table, dim, value, dateCol,
                a.path("period_a_start").asText(), a.path("period_a_end").asText(), a);
        Map<String, Double> bVals = periodSums(c, table, dim, value, dateCol,
                a.path("period_b_start").asText(), a.path("period_b_end").asText(), a);
        java.util.Set<String> keys = new java.util.TreeSet<>();
        keys.addAll(aVals.keySet());
        keys.addAll(bVals.keySet());
        double totalDelta = 0;
        List<double[]> rows = new ArrayList<>(); // index into keys parallel list
        List<String> keyList = new ArrayList<>(keys);
        for (String k : keyList) {
            double av = aVals.getOrDefault(k, 0.0);
            double bv = bVals.getOrDefault(k, 0.0);
            totalDelta += (bv - av);
            rows.add(new double[] {av, bv, bv - av});
        }
        // sort by absolute delta desc
        Integer[] order = new Integer[keyList.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (i, j) -> Double.compare(Math.abs(rows.get(j)[2]), Math.abs(rows.get(i)[2])));
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
                "driver analysis of %s by %s — total change = %s%n%s | period_a | period_b | delta | %% of change%n",
                value, dim, Stats.round(totalDelta, 2), dim));
        int shown = 0;
        for (int idx : order) {
            if (shown++ >= 15) {
                break;
            }
            double[] r = rows.get(idx);
            String share = totalDelta == 0 ? "—"
                    : String.format(Locale.ROOT, "%+.0f%%", 100.0 * r[2] / totalDelta);
            sb.append(String.format(Locale.ROOT, "%s | %s | %s | %s | %s%n",
                    keyList.get(idx), Stats.round(r[0], 0), Stats.round(r[1], 0), Stats.round(r[2], 0), share));
        }
        return sb.toString().strip();
    }

    private static Map<String, Double> periodSums(Connection c, String table, String dim, String value,
            String dateCol, String start, String end, JsonNode a) throws Exception {
        Map<String, Double> out = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT " + dim + " AS k, SUM(" + value + ") AS v FROM "
                + table + " WHERE " + dateCol + " >= ? AND " + dateCol + " <= ?" + andFilter(a)
                + " GROUP BY " + dim)) {
            ps.setString(1, start);
            ps.setString(2, end);
            bindFilter(ps, 3, a);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString("k"), rs.getDouble("v"));
                }
            }
        }
        return out;
    }

    // ---- plumbing -------------------------------------------------------------------------------

    // Flat scalar filter params: models fill these reliably, whereas a nested {column,value} object
    // trips them up (observed live: repeated invalid-argument retries and fallback to raw sql).
    private static final String FILTER =
            "\"filter_column\":{\"type\":\"string\"},\"filter_value\":{\"type\":\"string\"}";

    private static String obj(String properties, String... required) {
        StringBuilder req = new StringBuilder();
        for (int i = 0; i < required.length; i++) {
            req.append(i > 0 ? "," : "").append('"').append(required[i]).append('"');
        }
        return "{\"type\":\"object\",\"properties\":{" + properties + "},\"required\":[" + req + "]}";
    }

    private static String obj() {
        return "{\"type\":\"object\",\"properties\":{}}";
    }

    private static Tool tool(String url, String name, String description, String schema, SqlFn fn) {
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

    private static ResultSet query(Connection c, String sql) throws Exception {
        return c.createStatement().executeQuery(sql);
    }

    private static double[] numericColumn(Connection c, String table, String col, JsonNode a) throws Exception {
        List<Double> vals = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT " + col + " FROM " + table
                + " WHERE " + col + " IS NOT NULL" + andFilter(a))) {
            bindFilter(ps, 1, a);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String s = rs.getString(1);
                    if (s == null) {
                        continue;
                    }
                    try {
                        vals.add(Double.parseDouble(s));
                    } catch (NumberFormatException ignore) {
                        // non-numeric value; skip
                    }
                }
            }
        }
        double[] out = new double[vals.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = vals.get(i);
        }
        return out;
    }

    private static boolean hasFilter(JsonNode a) {
        return a.hasNonNull("filter_column") && a.hasNonNull("filter_value");
    }

    private static String whereFilter(JsonNode a) {
        return hasFilter(a) ? " WHERE " + Sql.ident(a.path("filter_column").asText()) + " = ?" : "";
    }

    private static String andFilter(JsonNode a) {
        String where = whereFilter(a);
        return where.isEmpty() ? "" : " AND " + where.substring(" WHERE ".length());
    }

    private static int bindFilter(PreparedStatement ps, int idx, JsonNode a) throws Exception {
        if (hasFilter(a)) {
            ps.setString(idx, a.path("filter_value").asText());
            return idx + 1;
        }
        return idx;
    }

    private static String toQuarter(String yyyyMm) {
        int month = Integer.parseInt(yyyyMm.substring(5, 7));
        return yyyyMm.substring(0, 4) + "-Q" + ((month - 1) / 3 + 1);
    }

    private static String interpret(double r) {
        if (Double.isNaN(r)) {
            return "undefined";
        }
        double abs = Math.abs(r);
        String strength = abs < 0.1 ? "negligible" : abs < 0.3 ? "weak" : abs < 0.6 ? "moderate" : "strong";
        return strength + (r >= 0 ? " positive" : " negative");
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double min(double[] xs) {
        double m = xs[0];
        for (double x : xs) {
            m = Math.min(m, x);
        }
        return m;
    }

    private static double max(double[] xs) {
        double m = xs[0];
        for (double x : xs) {
            m = Math.max(m, x);
        }
        return m;
    }

    private static String emptyToAny(String type) {
        return type == null || type.isBlank() ? "(any)" : type;
    }
}
