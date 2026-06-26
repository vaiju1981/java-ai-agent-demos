package dev.vaijanath.aiagent.demos;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Small SQL helpers shared by the data-backed demo toolkits: result formatting and an identifier guard. */
public final class Sql {

    private Sql() {}

    /** Formats a result set as a header line plus up to {@code maxRows} " | "-separated rows. */
    public static String table(ResultSet rs, int maxRows) throws SQLException {
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
        return sb.toString().strip();
    }

    /** Joins the first column's values with ", ". */
    public static String firstColumn(ResultSet rs) throws SQLException {
        List<String> out = new ArrayList<>();
        while (rs.next()) {
            out.add(rs.getString(1));
        }
        return String.join(", ", out);
    }

    /** Allows only simple identifiers, so an injected table/column name can't smuggle SQL. */
    public static String ident(String s) {
        if (s == null || !s.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("invalid identifier: " + s);
        }
        return s;
    }
}
