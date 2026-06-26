package dev.vaijanath.aiagent.demos.data;

import dev.vaijanath.aiagent.demos.Demos;
import dev.vaijanath.aiagent.demos.Governed;
import dev.vaijanath.aiagent.demos.ReportTool;
import dev.vaijanath.aiagent.model.ModelPort;
import dev.vaijanath.aiagent.tool.Tool;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * A real data analyst over a multi-table e-commerce warehouse (~1,500 customers, ~12,000 orders,
 * ~30,000 order lines) exposed as a denormalized {@code order_lines} mart. It is given an actual EDA
 * toolkit — profiling, histograms, IQR outliers, correlation, multi-dimension segmentation,
 * time-series with period-over-period change, and driver (contribution-to-change) analysis — and
 * asked the kind of questions an analyst really gets: explore the data, find seasonality, explain a
 * regional dip, locate thin margins, quantify promo elasticity, and surface a return hotspot.
 *
 * <p>The data has planted signal to discover (see {@link EcommerceData}); rows stay in SQLite, so
 * only aggregates enter the model. It runs through the governed runtime ({@link Governed}): every
 * turn is deadline-bounded, schema-validated, audited, and token-metered. Needs {@code AGENT_MODEL}.
 */
public final class DataAnalystDemo {

    private static final String SYSTEM_PROMPT = """
            You are a senior data analyst. The database has a denormalized view `order_lines` (one row
            per order line) with columns: order_id, order_date, order_month, channel, status
            (completed/returned/cancelled), discount_pct, customer_id, region, segment, signup_date,
            product_id, category, subcategory, list_price, unit_cost, quantity, unit_price, revenue,
            cost, margin. Base tables (customers, products, orders, order_items) are also queryable.

            Work like an analyst: start by understanding the schema, then use the right tool for the
            job — profile_column / histogram / outliers for EDA, correlation for relationships,
            group_stats for segmentation (one or two dimensions), time_series for trend and
            seasonality, and driver_analysis to explain a change. When you measure realized revenue,
            restrict to completed orders (filter_column=status, filter_value=completed), since
            returned/cancelled orders are not revenue. Prefer the structured tools; use `sql` only for
            something they don't cover.

            As you establish each concrete result, call record_finding(title, finding) to write it
            into the report — short section titles, the numbers you found, and any caveat. Cover at
            least: data overview & quality, revenue trend & seasonality, the Q3-vs-Q2 driver, margins
            by category/subcategory, promo (discount→quantity) elasticity, and the return hotspot.
            Finish with a 'Recommendations' finding. Then give a one-paragraph executive summary.""";

    public static void main(String[] args) throws Exception {
        ModelPort model = Demos.modelFromEnv();
        String db = EcommerceData.createDb(1_500, 12_000);
        ReportTool report = new ReportTool("2025 Revenue Health — Analyst Report");
        List<Tool> toolkit = new ArrayList<>(AnalyticsTools.toolkit(db));
        toolkit.add(report);

        System.out.println("== DataAnalystDemo ==  model: " + model.name());
        System.out.println(toolkit.size() + " tools over a multi-table e-commerce warehouse "
                + "(EDA + segmentation + drivers + report)\n");
        if (Demos.isStub(model)) {
            System.out.println("(set AGENT_MODEL to a tool-capable Ollama model — analysis needs the model to drive the tools)\n");
        }

        // A single deep investigation: the agent works through the analysis and writes up findings.
        Governed.Result governed = Governed.agent(model, toolkit, List.of(), SYSTEM_PROMPT, 28);
        String task = "Investigate the health of 2025 revenue end to end and produce the report: "
                + "explore and quality-check the data, find the trend and seasonality, explain the "
                + "Q3-vs-Q2 change, locate the thinnest margins, quantify promo elasticity, surface "
                + "any return hotspot, and recommend actions. Record each finding as you go.";

        System.out.println("> " + task + "\n");
        var request = Governed.request("acme-commerce", "analyst-1", "analysis-session", task,
                Duration.ofMinutes(5));
        String summary = governed.agent().run(request).output();

        System.out.println("==== PERSISTED REPORT (" + report.sectionCount() + " sections) ====");
        System.out.println(report.render());
        System.out.println("\n==== EXECUTIVE SUMMARY ====");
        System.out.println(summary + "\n");
        Governed.printTrustReport(governed.audit(), governed.tokens());
    }
}
