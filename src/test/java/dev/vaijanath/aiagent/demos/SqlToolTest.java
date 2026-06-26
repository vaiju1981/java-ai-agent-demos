package dev.vaijanath.aiagent.demos;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vaijanath.aiagent.tool.ToolResult;
import org.junit.jupiter.api.Test;

class SqlToolTest {

    @Test
    void runsSelectAndReturnsRows() throws Exception {
        String db = SyntheticData.createTransactionsDb(100);
        ToolResult r = new SqlTool(db, 50).invoke("{\"query\":\"SELECT COUNT(*) AS n FROM transactions\"}");
        assertFalse(r.error());
        assertTrue(r.content().contains(String.valueOf(SyntheticData.count(db, "transactions"))));
    }

    @Test
    void rejectsNonSelect() {
        // The read-only check runs before any DB access, so no real DB is needed.
        ToolResult r = new SqlTool("jdbc:sqlite::memory:", 50).invoke("{\"query\":\"DROP TABLE transactions\"}");
        assertTrue(r.error());
        assertTrue(r.content().toLowerCase().contains("read-only"));
    }

    @Test
    void capsResultsAtMaxRows() throws Exception {
        String db = SyntheticData.createTransactionsDb(100);
        ToolResult r = new SqlTool(db, 5).invoke("{\"query\":\"SELECT * FROM transactions\"}");
        assertFalse(r.error());
        assertTrue(r.content().contains("truncated"));
    }

    @Test
    void databaseRejectsAWriteHiddenBehindAWithClause() throws Exception {
        String db = SyntheticData.createTransactionsDb(10);
        long before = SyntheticData.count(db, "transactions");

        ToolResult r = new SqlTool(db, 5)
                .invoke("{\"query\":\"WITH chosen AS (SELECT id FROM transactions LIMIT 1) "
                        + "DELETE FROM transactions WHERE id IN (SELECT id FROM chosen) RETURNING id\"}");

        assertTrue(r.error());
        assertTrue(r.content().contains("readonly"));
        assertTrue(before == SyntheticData.count(db, "transactions"));
    }
}
