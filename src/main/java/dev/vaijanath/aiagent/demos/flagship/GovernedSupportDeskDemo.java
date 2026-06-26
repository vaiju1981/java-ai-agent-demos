package dev.vaijanath.aiagent.demos.flagship;

import dev.vaijanath.aiagent.agent.Agent;
import dev.vaijanath.aiagent.agent.AgentRequest;
import dev.vaijanath.aiagent.agent.AgentResponse;
import dev.vaijanath.aiagent.agent.ProductionAgentRuntime;
import dev.vaijanath.aiagent.agent.RequestContext;
import dev.vaijanath.aiagent.audit.AuditEvent;
import dev.vaijanath.aiagent.audit.InMemoryAuditSink;
import dev.vaijanath.aiagent.guardrail.CrisisGuardrail;
import dev.vaijanath.aiagent.guardrail.Guardrail;
import dev.vaijanath.aiagent.guardrail.PiiScrubGuardrail;
import dev.vaijanath.aiagent.memory.Memory;
import dev.vaijanath.aiagent.model.BudgetModelPort;
import dev.vaijanath.aiagent.model.Message;
import dev.vaijanath.aiagent.model.ModelPort;
import dev.vaijanath.aiagent.model.ModelRequest;
import dev.vaijanath.aiagent.model.ModelResponse;
import dev.vaijanath.aiagent.model.Role;
import dev.vaijanath.aiagent.model.TokenBudget;
import dev.vaijanath.aiagent.model.ToolCall;
import dev.vaijanath.aiagent.model.Usage;
import dev.vaijanath.aiagent.store.jdbc.JdbcConversationStore;
import dev.vaijanath.aiagent.tool.Tool;
import dev.vaijanath.aiagent.tool.ToolApprover;
import dev.vaijanath.aiagent.tool.ToolApprovers;
import dev.vaijanath.aiagent.tool.ToolEffect;
import dev.vaijanath.aiagent.tool.ToolResult;
import dev.vaijanath.aiagent.tool.ToolSpec;
import dev.vaijanath.aiagent.tools.jsonschema.JsonSchemaToolValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The flagship: a self-contained tour of the production trust layer on one governed support-desk
 * agent. Unlike the capability demos, it does not need Ollama — it uses a small scripted model so
 * every guarantee is deterministic and reproducible:
 *
 * <ol>
 *   <li>durable conversation history that survives a "restart" (real PostgreSQL/SQLite store);</li>
 *   <li>deny-by-default authorization of an effectful tool, then the same tool allow-listed;</li>
 *   <li>guardrails that block a crisis input and scrub PII before it reaches the model;</li>
 *   <li>a token budget that stops the run gracefully when exhausted;</li>
 *   <li>tenant isolation: the same session id under two tenants never crosses over;</li>
 *   <li>a full audit trail of who did what.</li>
 * </ol>
 *
 * The same {@code ProductionAgentRuntime} wiring backs the {@code production-reference} service.
 */
public final class GovernedSupportDeskDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("== GovernedSupportDeskDemo ==  a deterministic tour of the production trust layer");
        System.out.println("(scripted model — no Ollama needed; every guarantee below is reproducible)\n");

        Path dbFile = Files.createTempFile("flagship-", ".db");
        String jdbcUrl = "jdbc:sqlite:" + dbFile;
        InMemoryAuditSink audit = new InMemoryAuditSink();
        try {
            durableHistorySurvivesRestart(jdbcUrl, audit);
            denyByDefaultThenAllowListed(jdbcUrl, audit);
            guardrailsBlockAndScrub(jdbcUrl, audit);
            tokenBudgetStopsTheRunGracefully(jdbcUrl, audit);
            tenantIsolation(jdbcUrl, audit);
            printAuditTrail(audit);
        } finally {
            Files.deleteIfExists(dbFile);
        }
    }

    // 1 -----------------------------------------------------------------------------------------
    private static void durableHistorySurvivesRestart(String jdbcUrl, InMemoryAuditSink audit) {
        System.out.println("1) Durable history + restart");
        Agent agent = runtime(jdbcUrl, audit, new ScriptedModel(), ToolApprovers.denyEffectful(),
                List.of(), List.of());
        AgentResponse r = agent.run(request("acme", "agent-1", "order-status",
                "What is the status of order A-100?"));
        System.out.println("   turn → " + r.output());

        // A fresh store over the same database stands in for a process restart.
        JdbcConversationStore afterRestart = JdbcConversationStore.fromJdbcUrl(jdbcUrl);
        List<Message> history = afterRestart.withMemory("acme", "order-status", Memory::history);
        System.out.println("   after restart, durable history has " + history.size() + " messages\n");
    }

    // 2 -----------------------------------------------------------------------------------------
    private static void denyByDefaultThenAllowListed(String jdbcUrl, InMemoryAuditSink audit) {
        System.out.println("2) Deny-by-default effectful tool, then allow-listed");
        Tool refund = refundTool();

        Agent denying = runtime(jdbcUrl, audit, new ScriptedModel(), ToolApprovers.denyEffectful(),
                List.of(refund), List.of());
        AgentResponse denied = denying.run(request("acme", "agent-1", "refund-denied",
                "Please issue a refund for order A-100."));
        System.out.println("   default policy   → " + denied.output());

        Agent allowing = runtime(jdbcUrl, audit, new ScriptedModel(),
                ToolApprovers.denyEffectful("issue_refund"), List.of(refund), List.of());
        AgentResponse allowed = allowing.run(request("acme", "agent-1", "refund-allowed",
                "Please issue a refund for order A-100."));
        System.out.println("   allow-listed     → " + allowed.output() + "\n");
    }

    // 3 -----------------------------------------------------------------------------------------
    private static void guardrailsBlockAndScrub(String jdbcUrl, InMemoryAuditSink audit) {
        System.out.println("3) Guardrails: block a crisis, scrub PII");
        List<Guardrail> guardrails = List.of(new CrisisGuardrail(), new PiiScrubGuardrail());
        Agent agent = runtime(jdbcUrl, audit, new ScriptedModel(), ToolApprovers.denyEffectful(),
                List.of(), guardrails);

        AgentResponse crisis = agent.run(request("acme", "user-9", "crisis",
                "I want to hurt myself."));
        System.out.println("   crisis input  → " + (crisis.blocked() ? "[BLOCKED] " : "") + crisis.output());

        // The scripted model echoes what it received, proving the email was scrubbed before the model.
        AgentResponse pii = agent.run(request("acme", "user-9", "pii",
                "My email is jane@example.com — help with order A-100."));
        System.out.println("   PII input     → model saw: " + pii.output() + "\n");
    }

    // 4 -----------------------------------------------------------------------------------------
    private static void tokenBudgetStopsTheRunGracefully(String jdbcUrl, InMemoryAuditSink audit) {
        System.out.println("4) Token budget stops the run gracefully");
        ModelPort budgeted = new BudgetModelPort(new ScriptedModel(), new TokenBudget(10), 10);
        Agent agent = runtime(jdbcUrl, audit, budgeted, ToolApprovers.denyEffectful(),
                List.of(), List.of());

        AgentResponse first = agent.run(request("acme", "agent-1", "budget-1", "Summarize order A-100."));
        AgentResponse second = agent.run(request("acme", "agent-1", "budget-2", "Summarize order A-101."));
        System.out.println("   first turn  → stopReason=" + first.stopReason());
        System.out.println("   second turn → stopReason=" + second.stopReason() + " (budget exhausted)\n");
    }

    // 5 -----------------------------------------------------------------------------------------
    private static void tenantIsolation(String jdbcUrl, InMemoryAuditSink audit) {
        System.out.println("5) Tenant isolation (same session id, two tenants)");
        Agent agent = runtime(jdbcUrl, audit, new ScriptedModel(), ToolApprovers.denyEffectful(),
                List.of(), List.of());
        agent.run(request("acme", "a", "shared", "Note an acme detail."));
        agent.run(request("globex", "b", "shared", "Note a globex detail."));

        JdbcConversationStore store = JdbcConversationStore.fromJdbcUrl(jdbcUrl);
        int acme = store.withMemory("acme", "shared", m -> m.history().size());
        int globex = store.withMemory("globex", "shared", m -> m.history().size());
        System.out.println("   session 'shared': acme=" + acme + " msgs, globex=" + globex
                + " msgs — no cross-tenant leakage\n");
    }

    // -----------------------------------------------------------------------------------------
    private static void printAuditTrail(InMemoryAuditSink audit) {
        System.out.println("==== audit trail (" + audit.events().size() + " events) ====");
        for (AuditEvent e : audit.events()) {
            System.out.printf("  %-14s tenant=%-7s %s%n", e.type(), e.tenant(), e.detail());
        }
    }

    private static Agent runtime(String jdbcUrl, InMemoryAuditSink audit, ModelPort model,
            ToolApprover approver, List<Tool> tools, List<Guardrail> guardrails) {
        ProductionAgentRuntime.Builder builder = ProductionAgentRuntime.builder()
                .model(model)
                .conversationStore(JdbcConversationStore.fromJdbcUrl(jdbcUrl))
                .auditSink(audit)
                .argumentValidator(new JsonSchemaToolValidator())
                .toolApprover(approver)
                .systemPrompt("You are a support-desk agent. Be concise.");
        tools.forEach(builder::tool);
        guardrails.forEach(builder::guardrail);
        return builder.build();
    }

    private static AgentRequest request(String tenant, String principal, String session, String input) {
        RequestContext context = new RequestContext(
                session, principal, tenant, session, Instant.now().plus(Duration.ofSeconds(30)), Map.of());
        return new AgentRequest(input, context);
    }

    private static Tool refundTool() {
        return new Tool() {
            @Override
            public ToolSpec spec() {
                return new ToolSpec("issue_refund", "Issue a refund for an order.",
                        "{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"},"
                                + "\"amountCents\":{\"type\":\"integer\"}},\"required\":[\"orderId\",\"amountCents\"]}",
                        ToolEffect.EFFECTFUL);
            }

            @Override
            public ToolResult invoke(String argumentsJson) {
                return ToolResult.ok("refund processed: " + argumentsJson);
            }
        };
    }

    /** A deterministic stand-in for a real model: echoes input (proving guardrails), calls the refund
     *  tool when asked, and ends the loop after a tool result. */
    private static final class ScriptedModel implements ModelPort {
        @Override
        public ModelResponse chat(ModelRequest request) {
            Message last = request.messages().get(request.messages().size() - 1);
            if (last.role() == Role.TOOL) {
                return ModelResponse.text("Done — " + last.content(), new Usage(8, 4));
            }
            String userText = lastUserText(request);
            if (userText.toLowerCase(java.util.Locale.ROOT).contains("refund")) {
                return new ModelResponse("",
                        List.of(new ToolCall("c1", "issue_refund",
                                "{\"orderId\":\"A-100\",\"amountCents\":2599}")),
                        new Usage(20, 6));
            }
            return ModelResponse.text("Received: \"" + userText + "\"", new Usage(15, 8));
        }

        @Override
        public String name() {
            return "scripted";
        }

        private static String lastUserText(ModelRequest request) {
            return request.messages().stream()
                    .filter(m -> m.role() == Role.USER)
                    .reduce((a, b) -> b)
                    .map(Message::content)
                    .orElse("");
        }
    }
}
