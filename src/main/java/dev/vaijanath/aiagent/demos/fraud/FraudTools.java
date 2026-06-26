package dev.vaijanath.aiagent.demos.fraud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vaijanath.aiagent.demos.Sql;
import dev.vaijanath.aiagent.demos.SqlTool;
import dev.vaijanath.aiagent.tool.ContextualTool;
import dev.vaijanath.aiagent.tool.Tool;
import dev.vaijanath.aiagent.tool.ToolEffect;
import dev.vaijanath.aiagent.tool.ToolInvocation;
import dev.vaijanath.aiagent.tool.ToolResult;
import dev.vaijanath.aiagent.tool.ToolSpec;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The fraud investigator's toolkit. Read-only analysis — rank accounts by a risk signal, summarize an
 * account, pull its recent transactions, and a SQL escape hatch — plus two <b>effectful</b> actions
 * ({@code freeze_account}, {@code flag_for_review}) implemented as {@link ContextualTool}s so they run
 * under governance: denied by default, audited when allowed, and idempotent via the runtime's key.
 */
final class FraudTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, String> RISK_METRICS = Map.of(
            "amount", "ROUND(SUM(amount),2)",
            "count", "COUNT(*)",
            "foreign_countries", "COUNT(DISTINCT country)",
            "small_txns", "SUM(CASE WHEN amount < 5 THEN 1 ELSE 0 END)");

    private FraudTools() {}

    static List<Tool> toolkit(String jdbcUrl, CaseActions caseActions) {
        return List.of(
                new SqlTool(jdbcUrl, 50),
                suspiciousAccounts(jdbcUrl),
                accountSummary(jdbcUrl),
                recentTransactions(jdbcUrl),
                action(jdbcUrl, caseActions, "freeze_account", "freeze",
                        "Freeze an account to stop further transactions. Effectful — requires authorization."),
                action(jdbcUrl, caseActions, "flag_for_review", "flag",
                        "Flag an account for human review (does not stop transactions)."));
    }

    private static Tool suspiciousAccounts(String url) {
        String schema = "{\"type\":\"object\",\"properties\":{\"by\":{\"type\":\"string\","
                + "\"enum\":[\"amount\",\"count\",\"velocity\",\"foreign_countries\",\"small_txns\"]},"
                + "\"limit\":{\"type\":\"integer\"}},\"required\":[\"by\"]}";
        return readTool(url, "suspicious_accounts",
                "Rank accounts by a risk signal: amount (total moved), count, velocity (most "
                        + "transactions in any one hour), foreign_countries (distinct countries), or "
                        + "small_txns (sub-$5 charges, i.e. card testing). Start here.",
                schema, (c, a) -> {
                    String by = a.path("by").asText("");
                    int limit = clamp(a.path("limit").asInt(10), 1, 50);
                    String sql;
                    if ("velocity".equals(by)) {
                        sql = "SELECT account_id, MAX(cnt) AS per_hour FROM (SELECT account_id, "
                                + "substr(ts,1,13) AS hr, COUNT(*) AS cnt FROM transactions "
                                + "GROUP BY account_id, hr) GROUP BY account_id ORDER BY per_hour DESC LIMIT " + limit;
                    } else {
                        String expr = RISK_METRICS.get(by);
                        if (expr == null) {
                            return "unknown risk signal '" + by + "'";
                        }
                        sql = "SELECT account_id, " + expr + " AS " + by + " FROM transactions "
                                + "GROUP BY account_id ORDER BY " + by + " DESC LIMIT " + limit;
                    }
                    try (var st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                        return Sql.table(rs, limit);
                    }
                });
    }

    private static Tool accountSummary(String url) {
        return readTool(url, "account_summary",
                "Summarize one account: transaction count, total/avg amount, distinct countries and "
                        + "merchants, active window, and peak transactions-per-hour (velocity).",
                idSchema(), (c, a) -> {
                    String account = a.path("account_id").asText("");
                    String summary;
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT COUNT(*), ROUND(SUM(amount),2), ROUND(AVG(amount),2), "
                                    + "COUNT(DISTINCT country), COUNT(DISTINCT merchant), MIN(ts), MAX(ts) "
                                    + "FROM transactions WHERE account_id = ?")) {
                        ps.setString(1, account);
                        try (ResultSet rs = ps.executeQuery()) {
                            rs.next();
                            if (rs.getLong(1) == 0) {
                                return "no transactions for account " + account;
                            }
                            summary = String.format(Locale.ROOT,
                                    "account %s: txns=%d total=%s avg=%s countries=%d merchants=%d%n"
                                            + "  window: %s .. %s",
                                    account, rs.getLong(1), rs.getString(2), rs.getString(3),
                                    rs.getInt(4), rs.getInt(5), rs.getString(6), rs.getString(7));
                        }
                    }
                    long velocity;
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT MAX(cnt) FROM (SELECT substr(ts,1,13) hr, COUNT(*) cnt FROM transactions "
                                    + "WHERE account_id = ? GROUP BY hr)")) {
                        ps.setString(1, account);
                        try (ResultSet rs = ps.executeQuery()) {
                            rs.next();
                            velocity = rs.getLong(1);
                        }
                    }
                    return summary + "\n  peak velocity: " + velocity + " txns/hour";
                });
    }

    private static Tool recentTransactions(String url) {
        String schema = "{\"type\":\"object\",\"properties\":{\"account_id\":{\"type\":\"string\"},"
                + "\"limit\":{\"type\":\"integer\"}},\"required\":[\"account_id\"]}";
        return readTool(url, "recent_transactions",
                "List an account's most recent transactions (time, amount, merchant, country, channel).",
                schema, (c, a) -> {
                    int limit = clamp(a.path("limit").asInt(10), 1, 30);
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT ts, amount, merchant, country, channel, status FROM transactions "
                                    + "WHERE account_id = ? ORDER BY ts DESC LIMIT " + limit)) {
                        ps.setString(1, a.path("account_id").asText(""));
                        try (ResultSet rs = ps.executeQuery()) {
                            return Sql.table(rs, limit);
                        }
                    }
                });
    }

    /** An effectful action implemented as a ContextualTool so it runs under governance + idempotency. */
    private static Tool action(String url, CaseActions caseActions, String name, String type, String description) {
        return new ContextualTool() {
            @Override
            public ToolSpec spec() {
                return new ToolSpec(name, description,
                        "{\"type\":\"object\",\"properties\":{\"account_id\":{\"type\":\"string\"},"
                                + "\"reason\":{\"type\":\"string\"}},\"required\":[\"account_id\",\"reason\"]}",
                        ToolEffect.EFFECTFUL);
            }

            @Override
            public ToolResult invoke(ToolInvocation invocation) {
                JsonNode a;
                try {
                    a = MAPPER.readTree(invocation.argumentsJson());
                } catch (Exception e) {
                    return ToolResult.error(name + ": could not parse arguments");
                }
                String account = a.path("account_id").asText("");
                String reason = a.path("reason").asText("");
                if (account.isBlank() || reason.isBlank()) {
                    return ToolResult.error(name + ": account_id and reason are required");
                }
                if (!accountExists(url, account)) {
                    return ToolResult.error(name + ": unknown account " + account);
                }
                boolean applied = caseActions.apply(invocation.idempotencyKey(),
                        new CaseActions.Action(type, account, reason,
                                invocation.context().principal()));
                return ToolResult.ok((applied ? type + " applied to " : "no-op (already " + type + "ed) ")
                        + account + " by " + invocation.context().principal());
            }
        };
    }

    private static boolean accountExists(String url, String account) {
        try (Connection c = DriverManager.getConnection(url);
                PreparedStatement ps = c.prepareStatement("SELECT 1 FROM accounts WHERE account_id = ?")) {
            ps.setString(1, account);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            return false;
        }
    }

    @FunctionalInterface
    private interface SqlFn {
        String apply(Connection c, JsonNode args) throws Exception;
    }

    private static Tool readTool(String url, String name, String description, String schema, SqlFn fn) {
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

    private static String idSchema() {
        return "{\"type\":\"object\",\"properties\":{\"account_id\":{\"type\":\"string\"}},"
                + "\"required\":[\"account_id\"]}";
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
