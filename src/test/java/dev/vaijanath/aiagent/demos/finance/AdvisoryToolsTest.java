package dev.vaijanath.aiagent.demos.finance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vaijanath.aiagent.tool.Tool;
import dev.vaijanath.aiagent.tool.ToolResult;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Proves the advisory tools find the planted signal in {@link FinanceData} — declining savings rate,
 * dining lifestyle-creep, the new gym subscription, the anomalous trip, and the over-budget category.
 * Deterministic dataset, so the directional facts hold every run.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdvisoryToolsTest {

    private List<Tool> tools;

    @BeforeAll
    void setUp() throws Exception {
        tools = AdvisoryTools.toolkit(FinanceData.createDb(), FinanceData.BUDGETS);
    }

    @Test
    void cashFlowCoversTheWholeYear() {
        String out = run("cash_flow", "{}");
        long months = out.lines().filter(l -> l.startsWith("2025-")).count();
        assertTrue(months == 12, "cash flow should cover 12 months, got " + months + "\n" + out);
    }

    @Test
    void savingsRateDeclinesInTheSecondHalf() {
        String out = run("savings_rate", "{}");
        double h1 = pctAfter(out, "H1: ");
        double h2 = pctAfter(out, "H2: ");
        assertTrue(h2 < h1, "second-half savings rate should fall: H1=" + h1 + " H2=" + h2 + "\n" + out);
    }

    @Test
    void diningSpendingCreepsUp() {
        String out = run("spending_trend", "{\"category\":\"Dining\"}");
        assertTrue(monthValue(out, "2025-12") > monthValue(out, "2025-01"),
                "dining should rise from January to December:\n" + out);
    }

    @Test
    void detectsTheNewGymSubscription() {
        String out = run("detect_subscriptions", "{}");
        assertTrue(out.contains("FitGym"), "should surface the mid-year gym subscription:\n" + out);
        assertTrue(out.contains("Netflix"), "should surface the year-round subscriptions:\n" + out);
    }

    @Test
    void flagsTheAnomalousTrip() {
        String out = run("detect_anomalies", "{}");
        assertTrue(out.contains("Airline") && out.contains("3500"),
                "should flag the one-off $3,500 trip:\n" + out);
    }

    @Test
    void diningIsOverBudget() {
        String out = run("budget_variance", "{}");
        String dining = out.lines().filter(l -> l.startsWith("Dining")).findFirst().orElse("");
        assertTrue(dining.contains("OVER"), "dining should be over budget: " + dining);
    }

    @Test
    void goalProjectionComputesAHorizon() {
        String out = run("goal_projection", "{\"target_amount\":30000}");
        assertTrue(out.contains("months"), "goal projection should report a horizon: " + out);
    }

    // --- helpers ---------------------------------------------------------------------------------

    private String run(String name, String argsJson) {
        ToolResult r = tools.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow()
                .invoke(argsJson);
        assertFalse(r.error(), name + " errored: " + r.content());
        return r.content();
    }

    private static double pctAfter(String text, String marker) {
        int i = text.indexOf(marker) + marker.length();
        int j = i;
        while (j < text.length() && (Character.isDigit(text.charAt(j)) || ".-".indexOf(text.charAt(j)) >= 0)) {
            j++;
        }
        return Double.parseDouble(text.substring(i, j));
    }

    private static double monthValue(String table, String month) {
        return table.lines()
                .filter(l -> l.startsWith(month))
                .map(l -> l.split("\\|")[1].trim())
                .mapToDouble(Double::parseDouble)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for " + month + " in:\n" + table));
    }
}
