package dev.vaijanath.aiagent.demos.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vaijanath.aiagent.demos.SimpleTool;
import dev.vaijanath.aiagent.tool.ContextualTool;
import dev.vaijanath.aiagent.tool.Tool;
import dev.vaijanath.aiagent.tool.ToolEffect;
import dev.vaijanath.aiagent.tool.ToolInvocation;
import dev.vaijanath.aiagent.tool.ToolResult;
import dev.vaijanath.aiagent.tool.ToolSpec;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The support copilot's toolkit. One read-only lookup ({@code lookup_order}) plus two <b>effectful</b>
 * actions ({@code create_ticket}, {@code issue_refund}) implemented as {@link ContextualTool}s so they
 * run under governance: denied by default, audited when allowed, and idempotent via the runtime's key.
 *
 * <p>Authorization is graduated. {@code create_ticket} is low-risk and allow-listed, so the copilot
 * opens tickets autonomously. {@code issue_refund} moves money and is high-risk — it is denied without
 * human authorization. And even when a refund <em>is</em> authorized, the tool still enforces the
 * domain rule (the order must exist and be within the refund window), so policy holds either way.
 */
public final class SupportTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> PRIORITIES = Set.of("low", "normal", "high");

    private SupportTools() {}

    public static List<Tool> toolkit(OrderBook orders, SupportActions actions) {
        return List.of(lookupOrder(orders), createTicket(actions), issueRefund(orders, actions));
    }

    /** Read-only: report an order's status and refund eligibility. */
    private static Tool lookupOrder(OrderBook orders) {
        String schema = "{\"type\":\"object\",\"properties\":{\"order_id\":{\"type\":\"string\"}},"
                + "\"required\":[\"order_id\"]}";
        return new SimpleTool("lookup_order",
                "Look up an order by id to see its item, amount, status, and whether it is within the "
                        + "30-day refund window. Use this before promising or attempting a refund.",
                schema, a -> orders.describe(a.path("order_id").asText("")));
    }

    /** Effectful + allow-listed: open a support ticket (low-risk, the agent may do this autonomously). */
    private static Tool createTicket(SupportActions actions) {
        String schema = "{\"type\":\"object\",\"properties\":{\"order_id\":{\"type\":\"string\"},"
                + "\"subject\":{\"type\":\"string\"},\"priority\":{\"type\":\"string\","
                + "\"enum\":[\"low\",\"normal\",\"high\"]}},\"required\":[\"subject\"]}";
        return new ContextualTool() {
            @Override
            public ToolSpec spec() {
                return new ToolSpec("create_ticket",
                        "Open a support ticket for a human agent to follow up (e.g. to escalate a refund "
                                + "that needs authorization). Provide a clear subject, the order id if any, "
                                + "and a priority (low|normal|high).",
                        schema, ToolEffect.EFFECTFUL);
            }

            @Override
            public ToolResult invoke(ToolInvocation invocation) {
                JsonNode a = parse(invocation);
                if (a == null) {
                    return ToolResult.error("create_ticket: could not parse arguments");
                }
                String subject = a.path("subject").asText("");
                if (subject.isBlank()) {
                    return ToolResult.error("create_ticket: subject is required");
                }
                String orderId = a.path("order_id").asText("").trim();
                String priority = normalizePriority(a.path("priority").asText("normal"));
                SupportActions.TicketOutcome outcome = actions.openTicket(
                        invocation.idempotencyKey(), orderId, subject, priority, invocation.context().principal());
                SupportActions.Ticket t = outcome.ticket();
                String verb = outcome.created() ? "opened" : "no-op (already open)";
                return ToolResult.ok(String.format(Locale.ROOT,
                        "%s ticket %s [%s]%s — \"%s\"", verb, t.id(), t.priority(),
                        orderId.isBlank() ? "" : " for " + orderId, t.subject()));
            }
        };
    }

    /** Effectful + denied-by-default: refund an order. High-risk; needs human authorization. */
    private static Tool issueRefund(OrderBook orders, SupportActions actions) {
        String schema = "{\"type\":\"object\",\"properties\":{\"order_id\":{\"type\":\"string\"},"
                + "\"amount_cents\":{\"type\":\"integer\"},\"reason\":{\"type\":\"string\"}},"
                + "\"required\":[\"order_id\",\"reason\"]}";
        return new ContextualTool() {
            @Override
            public ToolSpec spec() {
                return new ToolSpec("issue_refund",
                        "Refund an order to the customer's original payment method. Effectful and "
                                + "high-risk — requires authorization. amount_cents is optional (defaults "
                                + "to the full order amount). Only delivered orders within the 30-day "
                                + "window are refundable.",
                        schema, ToolEffect.EFFECTFUL);
            }

            @Override
            public ToolResult invoke(ToolInvocation invocation) {
                JsonNode a = parse(invocation);
                if (a == null) {
                    return ToolResult.error("issue_refund: could not parse arguments");
                }
                String orderId = a.path("order_id").asText("").trim();
                String reason = a.path("reason").asText("");
                if (orderId.isBlank() || reason.isBlank()) {
                    return ToolResult.error("issue_refund: order_id and reason are required");
                }
                OrderBook.Order order = orders.find(orderId);
                if (order == null) {
                    return ToolResult.error("issue_refund: unknown order " + orderId);
                }
                if (!order.refundEligible()) {
                    // Domain guard: even an authorized refund respects the policy.
                    return ToolResult.error("issue_refund: " + order.id() + " is not refund-eligible ("
                            + orders.describe(order.id()) + ")");
                }
                long amount = a.path("amount_cents").asLong(0);
                if (amount <= 0) {
                    amount = order.amountCents();
                }
                if (amount > order.amountCents()) {
                    return ToolResult.error("issue_refund: amount " + OrderBook.money(amount)
                            + " exceeds the order total " + OrderBook.money(order.amountCents()));
                }
                boolean applied = actions.issueRefund(invocation.idempotencyKey(),
                        new SupportActions.Refund(order.id(), amount, reason, invocation.context().principal()));
                return ToolResult.ok((applied
                        ? "refunded " + OrderBook.money(amount) + " on "
                        : "no-op (already refunded) ") + order.id() + " by " + invocation.context().principal());
            }
        };
    }

    private static JsonNode parse(ToolInvocation invocation) {
        try {
            return MAPPER.readTree(invocation.argumentsJson());
        } catch (Exception e) {
            return null;
        }
    }

    private static String normalizePriority(String priority) {
        String p = priority == null ? "" : priority.trim().toLowerCase(Locale.ROOT);
        return PRIORITIES.contains(p) ? p : "normal";
    }
}
