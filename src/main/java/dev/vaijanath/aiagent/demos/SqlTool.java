package dev.vaijanath.aiagent.demos;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vaijanath.aiagent.tool.Tool;
import dev.vaijanath.aiagent.tool.ToolEffect;
import dev.vaijanath.aiagent.tool.ToolResult;
import dev.vaijanath.aiagent.tool.ToolSpec;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * A read-only SQL tool over a SQLite database. The agent writes a single {@code SELECT}; the tool
 * runs it and returns the (capped) rows. This is how the agent scales to large data: the rows stay
 * in the database — only the query's result enters the model's context.
 */
public final class SqlTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String jdbcUrl;
    private final int maxRows;

    public SqlTool(String jdbcUrl, int maxRows) {
        this.jdbcUrl = jdbcUrl;
        this.maxRows = maxRows;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "sql",
                "Run ONE read-only SQLite SELECT against the database and return the rows. "
                        + "Use it for any data question.",
                "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},"
                        + "\"required\":[\"query\"]}",
                ToolEffect.READ_ONLY);
    }

    @Override
    public ToolResult invoke(String argumentsJson) {
        String query;
        try {
            query = MAPPER.readTree(argumentsJson).path("query").asText("");
        } catch (Exception e) {
            return ToolResult.error("could not parse arguments: " + argumentsJson);
        }
        String normalized = query.strip().toLowerCase(Locale.ROOT);
        if (!(normalized.startsWith("select") || normalized.startsWith("with"))) {
            return ToolResult.error("only read-only SELECT queries are allowed");
        }

        try (Connection c = DriverManager.getConnection(jdbcUrl);
                Statement s = c.createStatement()) {
            // The database enforces the security property. Prefix checks alone are bypassable by
            // writable CTEs and comments.
            s.execute("PRAGMA query_only = ON");
            return executeQuery(s, query);
        } catch (SQLException e) {
            return ToolResult.error("SQL error: " + e.getMessage());
        }
    }

    private ToolResult executeQuery(Statement statement, String query) throws SQLException {
        try (ResultSet rs = statement.executeQuery(query)) {
            ResultSetMetaData md = rs.getMetaData();
            int cols = md.getColumnCount();
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= cols; i++) {
                sb.append(i > 1 ? " | " : "").append(md.getColumnLabel(i));
            }
            sb.append('\n');
            int n = 0;
            while (rs.next() && n < maxRows) {
                for (int i = 1; i <= cols; i++) {
                    sb.append(i > 1 ? " | " : "").append(rs.getString(i));
                }
                sb.append('\n');
                n++;
            }
            if (n == maxRows && rs.next()) {
                sb.append("... (truncated at ").append(maxRows).append(" rows)\n");
            }
            return ToolResult.ok(sb.toString().strip());
        }
    }
}
