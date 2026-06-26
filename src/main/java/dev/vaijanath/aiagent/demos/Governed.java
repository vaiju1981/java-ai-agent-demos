package dev.vaijanath.aiagent.demos;

import dev.vaijanath.aiagent.agent.Agent;
import dev.vaijanath.aiagent.agent.AgentRequest;
import dev.vaijanath.aiagent.agent.ProductionAgentRuntime;
import dev.vaijanath.aiagent.agent.RequestContext;
import dev.vaijanath.aiagent.audit.AuditEvent;
import dev.vaijanath.aiagent.audit.InMemoryAuditSink;
import dev.vaijanath.aiagent.guardrail.Guardrail;
import dev.vaijanath.aiagent.memory.InMemoryConversationStore;
import dev.vaijanath.aiagent.model.ModelPort;
import dev.vaijanath.aiagent.observe.TokenAccountingObserver;
import dev.vaijanath.aiagent.tool.Tool;
import dev.vaijanath.aiagent.tool.ToolApprover;
import dev.vaijanath.aiagent.tool.ToolApprovers;
import dev.vaijanath.aiagent.tools.jsonschema.JsonSchemaToolValidator;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Wires a demo's model + tools through the same governed production runtime the
 * {@code production-reference} service uses — so the demos showcase the trust layer, not just
 * tool-calling. Every governed run gets: a hard request deadline, deny-by-default tool authorization,
 * fail-closed JSON-schema argument validation, content guardrails, a durable-style conversation store,
 * redacted token metering, and a full audit trail.
 *
 * <p>The {@link InMemoryAuditSink} and {@link TokenAccountingObserver} are returned so a demo can
 * print the trust artifacts a real operator would care about (see {@link #printTrustReport}).
 */
public final class Governed {

    private Governed() {}

    /** A governed agent plus the handles needed to show what governance did. */
    public record Result(Agent agent, InMemoryAuditSink audit, TokenAccountingObserver tokens) {}

    public static Result agent(ModelPort model, List<Tool> tools, String systemPrompt) {
        return agent(model, tools, List.of(), systemPrompt, 8);
    }

    public static Result agent(
            ModelPort model, List<Tool> tools, List<Guardrail> guardrails, String systemPrompt) {
        return agent(model, tools, guardrails, systemPrompt, 8);
    }

    /** {@code maxSteps} bounds the tool-calls-per-turn — raise it for deep, multi-step analysis. */
    public static Result agent(ModelPort model, List<Tool> tools, List<Guardrail> guardrails,
            String systemPrompt, int maxSteps) {
        return agent(model, tools, guardrails, systemPrompt, maxSteps, ToolApprovers.denyEffectful());
    }

    /** With a custom tool policy — e.g. {@code denyEffectful("flag_for_review")} for graduated
     *  authorization where a low-risk action runs but a high-risk one is denied without approval. */
    public static Result agent(ModelPort model, List<Tool> tools, List<Guardrail> guardrails,
            String systemPrompt, int maxSteps, ToolApprover approver) {
        InMemoryAuditSink audit = new InMemoryAuditSink();
        TokenAccountingObserver tokens = new TokenAccountingObserver();
        ProductionAgentRuntime.Builder builder = ProductionAgentRuntime.builder()
                .model(model)
                .conversationStore(new InMemoryConversationStore())
                .auditSink(audit)
                .argumentValidator(new JsonSchemaToolValidator())
                .observer(tokens)
                .toolApprover(approver)
                .maxSteps(maxSteps)
                .systemPrompt(systemPrompt);
        tools.forEach(builder::tool);
        guardrails.forEach(builder::guardrail);
        return new Result(builder.build(), audit, tokens);
    }

    /** A turn under a real identity and a wall-clock deadline — both enforced by the governed runtime. */
    public static AgentRequest request(
            String tenant, String principal, String session, String input, Duration deadline) {
        RequestContext context = new RequestContext(
                session, principal, tenant, session, Instant.now().plus(deadline), Map.of());
        return new AgentRequest(input, context);
    }

    /** Prints the trust artifacts: token metering and the (non-sensitive) audit trail. */
    public static void printTrustReport(InMemoryAuditSink audit, TokenAccountingObserver tokens) {
        System.out.printf(
                "---- trust report ----%nmodel calls: %d   tokens: %d in / %d out%naudit trail:%n",
                tokens.modelCalls(), tokens.inputTokens(), tokens.outputTokens());
        for (AuditEvent event : audit.events()) {
            System.out.printf("  %-14s tenant=%s principal=%s  %s%n",
                    event.type(), event.tenant(), event.principal(), event.detail());
        }
        System.out.println();
    }
}
