package dev.vaijanath.aiagent.demos.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vaijanath.aiagent.audit.AuditEvent;
import dev.vaijanath.aiagent.demos.Governed;
import dev.vaijanath.aiagent.memory.Episode;
import dev.vaijanath.aiagent.model.ModelPort;
import dev.vaijanath.aiagent.model.ModelRequest;
import dev.vaijanath.aiagent.model.ModelResponse;
import dev.vaijanath.aiagent.model.StubModelPort;
import dev.vaijanath.aiagent.model.ToolCall;
import dev.vaijanath.aiagent.rag.InMemoryVectorStore;
import dev.vaijanath.aiagent.rag.RetrievedChunk;
import dev.vaijanath.aiagent.store.jdbc.JdbcEpisodicStore;
import dev.vaijanath.aiagent.tool.ContextualTool;
import dev.vaijanath.aiagent.tool.Tool;
import dev.vaijanath.aiagent.tool.ToolApprovers;
import dev.vaijanath.aiagent.tool.ToolCallContext;
import dev.vaijanath.aiagent.tool.ToolInvocation;
import dev.vaijanath.aiagent.tool.ToolResult;
import java.io.File;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Proves the Support Copilot's domain logic end to end without a model: the read tool reports refund
 * eligibility, the refund tool enforces the policy and is idempotent, the governed runtime enforces
 * graduated authorization (a ticket runs, a refund is denied), the KB grounds the right article, and
 * the episodic store recalls the relevant past lesson.
 */
class SupportToolsTest {

    private OrderBook orders;
    private SupportActions actions;
    private List<Tool> tools;

    @BeforeEach
    void setUp() {
        orders = new OrderBook();
        actions = new SupportActions();
        tools = SupportTools.toolkit(orders, actions);
    }

    @Test
    void lookupReportsRefundEligibility() {
        assertTrue(run("lookup_order", "{\"order_id\":\"ORD-1001\"}").contains("within the 30-day refund window"));
        assertTrue(run("lookup_order", "{\"order_id\":\"ORD-1002\"}").contains("past the 30-day refund window"));
        assertTrue(run("lookup_order", "{\"order_id\":\"ORD-1003\"}").contains("not yet delivered"));
    }

    @Test
    void refundToolEnforcesEligibilityPolicy() {
        // Eligible order: refunds.
        ToolResult ok = invokeEffectful("issue_refund",
                "{\"order_id\":\"ORD-1001\",\"reason\":\"changed mind\"}", "idem-ok");
        assertFalse(ok.error(), ok.content());
        assertTrue(ok.content().contains("refunded $89.99"), ok.content());

        // Past the window: refused even though invoked directly (the domain guard, not governance).
        ToolResult past = invokeEffectful("issue_refund",
                "{\"order_id\":\"ORD-1002\",\"reason\":\"changed mind\"}", "idem-past");
        assertTrue(past.error() && past.content().contains("not refund-eligible"), past.content());

        // Not delivered yet: also refused.
        ToolResult transit = invokeEffectful("issue_refund",
                "{\"order_id\":\"ORD-1003\",\"reason\":\"changed mind\"}", "idem-transit");
        assertTrue(transit.error() && transit.content().contains("not refund-eligible"), transit.content());
    }

    @Test
    void refundIsIdempotent() {
        ToolInvocation call = effectful("issue_refund",
                "{\"order_id\":\"ORD-1001\",\"reason\":\"changed mind\"}", "idem-1");
        ContextualTool refund = (ContextualTool) tool("issue_refund");

        ToolResult first = refund.invoke(call);
        ToolResult second = refund.invoke(call); // a retry with the same idempotency key

        assertFalse(first.error());
        assertTrue(second.content().contains("no-op"), second.content());
        assertEquals(1, actions.refunds().size(), "a retried refund must apply exactly once");
    }

    @Test
    void ticketIsIdempotentAndReturnsStableId() {
        ToolInvocation call = effectful("create_ticket",
                "{\"order_id\":\"ORD-1001\",\"subject\":\"escalate refund\",\"priority\":\"normal\"}", "idem-t");
        ContextualTool ticket = (ContextualTool) tool("create_ticket");

        ToolResult first = ticket.invoke(call);
        ToolResult second = ticket.invoke(call); // a retry with the same idempotency key

        assertEquals(1, actions.tickets().size(), "a retried ticket must open exactly once");
        String id = actions.tickets().get(0).id();
        assertTrue(first.content().contains(id) && second.content().contains(id), "retry returns the same id");
        assertTrue(second.content().contains("no-op"), second.content());
    }

    @Test
    void graduatedAuthorizationAllowsTicketButDeniesRefund() {
        // A scripted copilot: open a ticket (allowed), then attempt a refund (denied without authorization).
        ModelPort scripted = new ModelPort() {
            private int call = 0;

            @Override
            public ModelResponse chat(ModelRequest request) {
                call++;
                if (call == 1) {
                    return new ModelResponse("", List.of(new ToolCall("c1", "create_ticket",
                            "{\"order_id\":\"ORD-1001\",\"subject\":\"escalate refund\",\"priority\":\"normal\"}")));
                }
                if (call == 2) {
                    return new ModelResponse("", List.of(new ToolCall("c2", "issue_refund",
                            "{\"order_id\":\"ORD-1001\",\"reason\":\"changed mind\"}")));
                }
                return ModelResponse.text("done");
            }

            @Override
            public String name() {
                return "scripted";
            }
        };
        Governed.Result governed = Governed.agent(scripted, tools, List.of(), "support", 6,
                ToolApprovers.denyEffectful("create_ticket"));

        governed.agent().run(Governed.request(
                SupportKb.TENANT, "agent", "case", "handle it", Duration.ofMinutes(1)));

        assertEquals(1, actions.tickets().size(), "the allow-listed ticket should have opened");
        assertTrue(actions.refunds().isEmpty(), "the refund must be denied without authorization");
        assertTrue(auditHas(governed.audit().events(), "tool.allowed", "create_ticket"));
        assertTrue(auditHas(governed.audit().events(), "tool.denied", "issue_refund"));
    }

    @Test
    void knowledgeBaseGroundsTheRefundPolicy() {
        InMemoryVectorStore kb = SupportKb.vectorStore(SupportKb.embedder(new StubModelPort()));
        List<RetrievedChunk> hits = kb.retrieve(SupportKb.TENANT, "What is your refund policy and the return window?", 1);
        assertFalse(hits.isEmpty(), "should retrieve a KB article");
        assertEquals("refund-policy", hits.get(0).id(), "the refund question should ground in the refund policy");
    }

    @Test
    void episodicMemoryRecallsTheRelevantLesson() throws Exception {
        File db = File.createTempFile("support-ep-test", ".db");
        db.deleteOnExit();
        JdbcEpisodicStore episodes =
                JdbcEpisodicStore.fromJdbcUrl("jdbc:sqlite:" + db.getAbsolutePath(), SupportKb.embedder(new StubModelPort()));
        episodes.record(new Episode(SupportKb.TENANT,
                "Customer wanted a cash refund for a rain jacket that lost waterproofing after water and accidental damage",
                "Declined the refund, offered a goodwill code, opened a ticket.",
                true,
                "Water and accidental damage are not covered by the limited warranty — do not issue a cash refund."));
        episodes.record(new Episode(SupportKb.TENANT,
                "Customer asked why their standard-shipping order had not arrived after a week",
                "Checked tracking, found a carrier delay, offered free express reshipment.",
                true,
                "For shipping-delay complaints, check tracking and offer free express reshipment when clearly late."));

        List<Episode> recalled = episodes.recall(SupportKb.TENANT,
                "rain jacket stopped being waterproof after water damage, customer wants a refund", 3);

        assertFalse(recalled.isEmpty(), "should recall a relevant past resolution");
        assertTrue(recalled.get(0).lesson().contains("Water and accidental damage"),
                "the water-damage lesson should rank first, not the shipping one: " + recalled.get(0).lesson());
    }

    // --- helpers ---------------------------------------------------------------------------------

    private String run(String name, String argsJson) {
        ToolResult r = tool(name).invoke(argsJson);
        assertFalse(r.error(), name + " errored: " + r.content());
        return r.content();
    }

    private ToolResult invokeEffectful(String name, String argsJson, String idemKey) {
        return ((ContextualTool) tool(name)).invoke(effectful(name, argsJson, idemKey));
    }

    private ToolInvocation effectful(String name, String argsJson, String idemKey) {
        Tool t = tool(name);
        ToolCall call = new ToolCall("c", name, argsJson);
        ToolCallContext ctx = new ToolCallContext(
                t.spec(), argsJson, "agent", SupportKb.TENANT, "trace", "sess", null, idemKey);
        return new ToolInvocation(call, ctx);
    }

    private Tool tool(String name) {
        return tools.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    private static boolean auditHas(List<AuditEvent> events, String type, String detailContains) {
        return events.stream().anyMatch(e -> e.type().equals(type) && e.detail().contains(detailContains));
    }
}
