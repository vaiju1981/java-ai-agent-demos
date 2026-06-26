package dev.vaijanath.aiagent.demos.support;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The record of effectful actions the copilot takes — support tickets it opens and refunds it issues.
 * Actions are <b>idempotent by key</b>: a retried tool call with the same idempotency key opens the
 * same ticket (and returns its id) or applies the same refund exactly once, so a re-run never
 * double-refunds a customer — mirroring how a real ticketing/payments service dedupes.
 */
public final class SupportActions {

    /** A support ticket; {@code id} is assigned by the ledger so retries get a stable identifier. */
    public record Ticket(String id, String orderId, String subject, String priority, String principal) {}

    /** A refund applied to an order, with who authorized it. */
    public record Refund(String orderId, long amountCents, String reason, String principal) {}

    /** The outcome of {@link #openTicket}: the ticket, and whether this call created it. */
    public record TicketOutcome(Ticket ticket, boolean created) {}

    private final Map<String, Ticket> ticketsByKey = new ConcurrentHashMap<>();
    private final List<Ticket> tickets = new CopyOnWriteArrayList<>();
    private final Map<String, Refund> refundsByKey = new ConcurrentHashMap<>();
    private final List<Refund> refunds = new CopyOnWriteArrayList<>();
    private final AtomicInteger ticketSeq = new AtomicInteger();

    /** Opens a ticket, or returns the existing one for a retried key (idempotent). */
    public TicketOutcome openTicket(
            String idempotencyKey, String orderId, String subject, String priority, String principal) {
        boolean[] created = {false};
        Ticket ticket = ticketsByKey.computeIfAbsent(idempotencyKey, k -> {
            created[0] = true;
            return new Ticket(String.format(Locale.ROOT, "TCK-%03d", ticketSeq.incrementAndGet()),
                    orderId, subject, priority, principal);
        });
        if (created[0]) {
            tickets.add(ticket);
        }
        return new TicketOutcome(ticket, created[0]);
    }

    /** Applies a refund unless its idempotency key was already seen; true if newly applied. */
    public boolean issueRefund(String idempotencyKey, Refund refund) {
        if (refundsByKey.putIfAbsent(idempotencyKey, refund) != null) {
            return false; // a retry of an already-applied refund
        }
        refunds.add(refund);
        return true;
    }

    public List<Ticket> tickets() {
        return List.copyOf(tickets);
    }

    public List<Refund> refunds() {
        return List.copyOf(refunds);
    }

    public String render() {
        if (tickets.isEmpty() && refunds.isEmpty()) {
            return "(no actions taken)";
        }
        StringBuilder sb = new StringBuilder();
        for (Ticket t : tickets) {
            sb.append(String.format(Locale.ROOT, "  [ticket %s] %s (%s) for %s by %s%n",
                    t.id(), t.subject(), t.priority(), t.orderId(), t.principal()));
        }
        for (Refund r : refunds) {
            sb.append(String.format(Locale.ROOT, "  [refund] %s on %s by %s — %s%n",
                    OrderBook.money(r.amountCents()), r.orderId(), r.principal(), r.reason()));
        }
        return sb.toString().strip();
    }
}
