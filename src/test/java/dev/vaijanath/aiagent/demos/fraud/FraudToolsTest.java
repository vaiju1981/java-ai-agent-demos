package dev.vaijanath.aiagent.demos.fraud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vaijanath.aiagent.audit.AuditEvent;
import dev.vaijanath.aiagent.demos.Governed;
import dev.vaijanath.aiagent.model.ModelPort;
import dev.vaijanath.aiagent.model.ModelRequest;
import dev.vaijanath.aiagent.model.ModelResponse;
import dev.vaijanath.aiagent.model.ToolCall;
import dev.vaijanath.aiagent.tool.ContextualTool;
import dev.vaijanath.aiagent.tool.Tool;
import dev.vaijanath.aiagent.tool.ToolApprovers;
import dev.vaijanath.aiagent.tool.ToolCallContext;
import dev.vaijanath.aiagent.tool.ToolInvocation;
import dev.vaijanath.aiagent.tool.ToolResult;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Proves the fraud domain end to end: the risk-signal tools surface the planted fraud, effectful
 * actions are idempotent, and the governed runtime enforces graduated authorization (a flag runs, a
 * freeze is denied without authorization).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FraudToolsTest {

    private String db;
    private CaseActions caseActions;
    private List<Tool> tools;

    @BeforeAll
    void setUp() throws Exception {
        db = FraudData.createDb(400);
        caseActions = new CaseActions();
        tools = FraudTools.toolkit(db, caseActions);
    }

    @Test
    void cardTestingTopsTheSmallTransactionSignal() {
        String out = run("suspicious_accounts", "{\"by\":\"small_txns\",\"limit\":3}");
        assertEquals("ACC-CARDTEST", topAccount(out), "card-testing account should top sub-$5 charges:\n" + out);
    }

    @Test
    void geoAnomalyTopsTheForeignCountriesSignal() {
        String out = run("suspicious_accounts", "{\"by\":\"foreign_countries\",\"limit\":3}");
        assertEquals("ACC-GEO", topAccount(out), "geo-anomaly account should top distinct countries:\n" + out);
    }

    @Test
    void theRingTopsTheAmountSignal() {
        String out = run("suspicious_accounts", "{\"by\":\"amount\",\"limit\":3}");
        assertTrue(topAccount(out).startsWith("ACC-RING"), "the transfer ring should top total amount:\n" + out);
    }

    @Test
    void accountSummaryReportsVelocity() {
        String out = run("account_summary", "{\"account_id\":\"ACC-CARDTEST\"}");
        assertTrue(out.contains("txns=60"), "should count all 60 charges: " + out);
        assertTrue(out.contains("60 txns/hour"), "should detect the one-hour velocity burst: " + out);
    }

    @Test
    void effectfulActionsAreIdempotent() {
        ContextualTool freeze = (ContextualTool) tool("freeze_account");
        ToolCall call = new ToolCall("c1", "freeze_account",
                "{\"account_id\":\"ACC-RING-1\",\"reason\":\"ring\"}");
        ToolCallContext ctx = new ToolCallContext(
                freeze.spec(), call.argumentsJson(), "analyst", "acme", "trace", "sess", null, "idem-1");
        ToolInvocation invocation = new ToolInvocation(call, ctx);

        ToolResult first = freeze.invoke(invocation);
        ToolResult second = freeze.invoke(invocation); // a retry with the same idempotency key

        assertFalse(first.error());
        assertFalse(second.error());
        assertEquals(1, caseActions.actions().size(), "a retried action must apply exactly once");
    }

    @Test
    void graduatedAuthorizationAllowsFlagButDeniesFreeze() {
        CaseActions actions = new CaseActions();
        List<Tool> kit = FraudTools.toolkit(db, actions);
        // A scripted investigator: flag one account, then try to freeze another.
        ModelPort scripted = new ModelPort() {
            private int call = 0;

            @Override
            public ModelResponse chat(ModelRequest request) {
                call++;
                if (call == 1) {
                    return new ModelResponse("", List.of(new ToolCall("c1", "flag_for_review",
                            "{\"account_id\":\"ACC-CARDTEST\",\"reason\":\"card testing\"}")));
                }
                if (call == 2) {
                    return new ModelResponse("", List.of(new ToolCall("c2", "freeze_account",
                            "{\"account_id\":\"ACC-RING-1\",\"reason\":\"transfer ring\"}")));
                }
                return ModelResponse.text("done");
            }

            @Override
            public String name() {
                return "scripted";
            }
        };
        Governed.Result governed = Governed.agent(scripted, kit, List.of(), "investigate", 6,
                ToolApprovers.denyEffectful("flag_for_review"));

        governed.agent().run(
                Governed.request("acme-pay", "analyst", "case", "investigate", java.time.Duration.ofMinutes(1)));

        assertEquals(1, actions.actions().size(), "only the allow-listed flag should have applied");
        assertEquals("flag", actions.actions().get(0).type());
        assertTrue(auditHas(governed.audit().events(), "tool.allowed", "flag_for_review"));
        assertTrue(auditHas(governed.audit().events(), "tool.denied", "freeze_account"));
    }

    // --- helpers ---------------------------------------------------------------------------------

    private String run(String name, String argsJson) {
        ToolResult r = tool(name).invoke(argsJson);
        assertFalse(r.error(), name + " errored: " + r.content());
        return r.content();
    }

    private Tool tool(String name) {
        return tools.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    /** The account id in the first data row of a "account_id | metric" table. */
    private static String topAccount(String table) {
        String[] lines = table.split("\n");
        return lines.length < 2 ? "" : lines[1].split("\\|")[0].trim();
    }

    private static boolean auditHas(List<AuditEvent> events, String type, String detailContains) {
        return events.stream().anyMatch(e -> e.type().equals(type) && e.detail().contains(detailContains));
    }
}
