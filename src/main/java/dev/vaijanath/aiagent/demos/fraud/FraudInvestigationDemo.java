package dev.vaijanath.aiagent.demos.fraud;

import dev.vaijanath.aiagent.demos.Demos;
import dev.vaijanath.aiagent.demos.Governed;
import dev.vaijanath.aiagent.demos.ReportTool;
import dev.vaijanath.aiagent.model.ModelPort;
import dev.vaijanath.aiagent.tool.Tool;
import dev.vaijanath.aiagent.tool.ToolApprovers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * A fraud/risk investigator — the demo that exercises the trust layer's differentiator: <b>effectful,
 * governed actions</b>. Over a payments dataset with planted fraud (card testing, a velocity spike, a
 * geo-anomaly, a transfer ring), the agent ranks accounts by risk signals, investigates the worst
 * offenders, writes up a case report, and takes action.
 *
 * <p>Authorization is <b>graduated</b>: the policy is deny-by-default with only {@code flag_for_review}
 * allow-listed. So the agent can autonomously flag suspicious accounts for review, but an attempt to
 * {@code freeze_account} (high-risk) is <b>denied</b> — it needs human authorization the agent doesn't
 * have. Every decision is audited; actions are idempotent. Needs {@code AGENT_MODEL}.
 */
public final class FraudInvestigationDemo {

    private static final String SYSTEM_PROMPT = """
            You are a payments fraud investigator. Tools: suspicious_accounts ranks accounts by a risk
            signal (amount, count, velocity, foreign_countries, small_txns); account_summary and
            recent_transactions inspect one account; `sql` is a read-only escape hatch. To act:
            flag_for_review marks an account for a human; freeze_account stops it (high-risk).

            Investigate methodically: scan each risk signal, then drill into the standout accounts to
            confirm the pattern (card testing = many sub-$5 charges; velocity = a burst in one hour;
            geo-anomaly = many countries fast; a ring = large repeated P2P transfers). record_finding
            for each confirmed case with the account id, the evidence, and your recommended action.
            Flag every confirmed-suspicious account for review. For the clearest fraud, also attempt a
            freeze — if it is denied by policy, note in your report that it is escalated for human
            authorization. Be specific and cite the numbers.""";

    public static void main(String[] args) {
        ModelPort model = Demos.modelFromEnv();
        String db;
        try {
            db = FraudData.createDb(400);
        } catch (Exception e) {
            throw new IllegalStateException("failed to build fraud dataset", e);
        }
        CaseActions caseActions = new CaseActions();
        ReportTool report = new ReportTool("Fraud Investigation — Case Report");
        List<Tool> toolkit = new ArrayList<>(FraudTools.toolkit(db, caseActions));
        toolkit.add(report);

        System.out.println("== FraudInvestigationDemo ==  model: " + model.name());
        System.out.println(toolkit.size() + " tools; policy: deny-by-default, only flag_for_review allow-listed\n");
        if (Demos.isStub(model)) {
            System.out.println("(set AGENT_MODEL to a tool-capable Ollama model — the investigation needs the model)\n");
        }

        // Graduated authorization: flag_for_review runs; freeze_account is denied without approval.
        Governed.Result governed = Governed.agent(model, toolkit, List.of(), SYSTEM_PROMPT, 30,
                ToolApprovers.denyEffectful("flag_for_review"));

        String task = "Investigate this payment book for fraud. Rank by each risk signal, confirm the "
                + "standout accounts, write up the cases, flag them for review, and attempt to freeze "
                + "the clearest fraud.";
        System.out.println("> " + task + "\n");
        var request = Governed.request("acme-pay", "fraud-analyst-1", "case-2025-03", task,
                Duration.ofMinutes(5));
        String summary = governed.agent().run(request).output();

        System.out.println("==== CASE REPORT (" + report.sectionCount() + " sections) ====");
        System.out.println(report.render());
        System.out.println("\n==== ACTIONS TAKEN (governed + idempotent) ====");
        System.out.println(caseActions.render());
        System.out.println("\n==== INVESTIGATOR SUMMARY ====");
        System.out.println(summary + "\n");
        Governed.printTrustReport(governed.audit(), governed.tokens());
    }
}
