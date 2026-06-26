package dev.vaijanath.aiagent.demos.support;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The store's synthetic order system — the deterministic ground truth the copilot's read-only
 * {@code lookup_order} tool reads and its effectful {@code issue_refund} tool validates against.
 *
 * <p>Refund eligibility is a real rule, not a stub: an order is refundable only if it has been
 * <b>delivered</b> and is still inside the 30-day window from delivery (the same policy the knowledge
 * base states). An in-transit order or one past the window is not refundable — so the agent can't
 * issue a refund the policy wouldn't allow, even before governance has its say.
 */
public final class OrderBook {

    /** One order; amounts are in cents to avoid floating-point money. */
    public record Order(String id, String item, long amountCents, String status, int daysSinceDelivery) {

        public boolean delivered() {
            return "delivered".equals(status);
        }

        /** Refundable only if delivered and still within the 30-day return window. */
        public boolean refundEligible() {
            return delivered() && daysSinceDelivery <= 30;
        }
    }

    private final Map<String, Order> orders = new LinkedHashMap<>();

    /** A small, believable catalogue covering every eligibility branch the demo exercises. */
    public OrderBook() {
        put(new Order("ORD-1001", "Summit 30L Backpack", 8999, "delivered", 10)); // eligible
        put(new Order("ORD-1002", "Trail Runner Shoes", 12_950, "delivered", 45)); // past 30-day window
        put(new Order("ORD-1003", "Alpine Down Sleeping Bag", 15_900, "in_transit", -1)); // not delivered
        put(new Order("ORD-1004", "Trailblazer Rain Jacket", 18_999, "delivered", 6)); // in window, but a damage claim
    }

    private void put(Order o) {
        orders.put(o.id(), o);
    }

    public Order find(String orderId) {
        return orders.get(orderId == null ? "" : orderId.trim().toUpperCase(Locale.ROOT));
    }

    /** A line the {@code lookup_order} tool returns to the model (and the demo prints). */
    public String describe(String orderId) {
        Order o = find(orderId);
        if (o == null) {
            return "no such order: " + orderId;
        }
        String window = switch (o.status()) {
            case "delivered" -> o.refundEligible()
                    ? "delivered " + o.daysSinceDelivery() + "d ago — within the 30-day refund window"
                    : "delivered " + o.daysSinceDelivery() + "d ago — past the 30-day refund window";
            case "in_transit" -> "in transit — not yet delivered, so not refundable";
            default -> o.status();
        };
        return String.format(Locale.ROOT, "%s: \"%s\" — %s — %s", o.id(), o.item(), money(o.amountCents()), window);
    }

    public static String money(long cents) {
        return String.format(Locale.ROOT, "$%,.2f", cents / 100.0);
    }
}
