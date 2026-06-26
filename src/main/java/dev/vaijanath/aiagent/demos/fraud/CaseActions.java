package dev.vaijanath.aiagent.demos.fraud;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The record of governed, effectful actions an investigation takes (freezes, review flags). Actions
 * are <b>idempotent by key</b>: a retried tool call with the same idempotency key is applied once, so
 * a re-run never double-freezes an account — mirroring how a real action service would dedupe.
 */
public final class CaseActions {

    /** One effectful action, with who authorized it. */
    public record Action(String type, String accountId, String reason, String principal) {}

    private final List<Action> actions = new CopyOnWriteArrayList<>();
    private final Set<String> appliedKeys = ConcurrentHashMap.newKeySet();

    /** Applies the action unless its idempotency key was already seen; true if newly applied. */
    public boolean apply(String idempotencyKey, Action action) {
        if (!appliedKeys.add(idempotencyKey)) {
            return false; // a retry of an already-applied action
        }
        actions.add(action);
        return true;
    }

    public List<Action> actions() {
        return List.copyOf(actions);
    }

    public String render() {
        if (actions.isEmpty()) {
            return "(no actions taken)";
        }
        StringBuilder sb = new StringBuilder();
        for (Action a : actions) {
            sb.append(String.format("  [%s] %s by %s — %s%n", a.type(), a.accountId(), a.principal(), a.reason()));
        }
        return sb.toString().strip();
    }
}
