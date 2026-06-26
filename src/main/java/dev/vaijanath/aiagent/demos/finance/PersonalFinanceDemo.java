package dev.vaijanath.aiagent.demos.finance;

import dev.vaijanath.aiagent.demos.Demos;
import dev.vaijanath.aiagent.demos.Governed;
import dev.vaijanath.aiagent.demos.ReportTool;
import dev.vaijanath.aiagent.model.ModelPort;
import dev.vaijanath.aiagent.tool.Tool;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * A personal financial advisor over a real year of income and expenses. It is given an actual
 * advisory toolkit — cash-flow and savings-rate analysis, spending trends, budget variance with a
 * year-end forecast, subscription and anomaly detection, goal projection, and planning calculators —
 * and asked to do a full review: where the money goes, whether savings are holding up, what's
 * creeping, what's anomalous, and what to change.
 *
 * <p>The data has planted signal to find (see {@link FinanceData}): dining lifestyle-creep, a new
 * mid-year subscription, an anomalous trip, and a declining second-half savings rate. It runs as one
 * investigation through the governed runtime ({@link Governed}) and writes a persisted **financial
 * plan** via {@code record_finding}. Needs {@code AGENT_MODEL}.
 */
public final class PersonalFinanceDemo {

    private static final String SYSTEM_PROMPT = """
            You are a personal financial advisor. The `transactions` table has columns: txn_date, type
            (income or expense), category, merchant, amount — one year (2025) for one household.

            Do a real review using the tools: cash_flow (monthly income/expense/net), savings_rate
            (overall and H1 vs H2), spending_by_category and spending_trend (watch for creep),
            budget_variance (avg vs budget with a year-end forecast), detect_subscriptions (new or
            forgotten recurring charges), detect_anomalies (one-off shocks), and goal_projection /
            the calculators for planning. As you establish each result, call record_finding(title,
            finding) with the numbers. Cover at least: cash flow & savings rate (and any decline),
            the biggest spending trend, budget overruns, subscriptions, anomalies, and a concrete
            'Recommendations' section. Finish with a short executive summary.""";

    public static void main(String[] args) throws Exception {
        ModelPort model = Demos.modelFromEnv();
        String db = FinanceData.createDb();
        ReportTool plan = new ReportTool("2025 Personal Financial Plan");
        List<Tool> toolkit = new ArrayList<>(AdvisoryTools.toolkit(db, FinanceData.BUDGETS));
        toolkit.add(plan);

        System.out.println("== PersonalFinanceDemo ==  model: " + model.name());
        System.out.println(toolkit.size() + " advisory tools over a year of income + expenses\n");
        if (Demos.isStub(model)) {
            System.out.println("(set AGENT_MODEL to a tool-capable Ollama model — the review needs the model)\n");
        }

        Governed.Result governed = Governed.agent(model, toolkit, List.of(), SYSTEM_PROMPT, 28);
        String task = "Review my 2025 finances end to end and build a financial plan: analyze cash "
                + "flow and the savings-rate trend, find what's driving spending up, check budgets with "
                + "a year-end forecast, surface subscriptions and any anomalous spend, and recommend "
                + "concrete actions with a goal projection. Record each finding as you go.";

        System.out.println("> " + task + "\n");
        var request = Governed.request("household-smith", "member-1", "review-session", task,
                Duration.ofMinutes(5));
        String summary = governed.agent().run(request).output();

        System.out.println("==== FINANCIAL PLAN (" + plan.sectionCount() + " sections) ====");
        System.out.println(plan.render());
        System.out.println("\n==== EXECUTIVE SUMMARY ====");
        System.out.println(summary + "\n");
        Governed.printTrustReport(governed.audit(), governed.tokens());
    }
}
