package dev.vaijanath.aiagent.demos.observe;

import dev.vaijanath.aiagent.model.Pricing;
import dev.vaijanath.aiagent.model.TokenPrice;
import dev.vaijanath.aiagent.model.Usage;
import dev.vaijanath.aiagent.observe.TokenAccountingObserver;
import java.util.Map;

/**
 * Observability: per-model token accounting and <b>bring-your-own</b> cost. A
 * {@link TokenAccountingObserver} attributes usage to the model that produced it (e.g. a supervisor on a
 * large model, workers on a small one), and a {@link Pricing} table turns tokens into dollars — no prices
 * are bundled, so list/negotiated rates are yours to set; unpriced (local) models are free.
 *
 * <p>Deterministic — it feeds the observer representative multi-model usage rather than needing a live run
 * (wire {@code .observer(acct)} into any agent for live numbers).
 *
 * <pre>{@code
 * ./gradlew run -PmainClass=dev.vaijanath.aiagent.demos.observe.ObservabilityDemo
 * }</pre>
 */
public final class ObservabilityDemo {

    private ObservabilityDemo() {}

    /** Representative usage from a two-model run, recorded as the runtime would via {@code onUsage}. */
    static TokenAccountingObserver simulatedRun() {
        TokenAccountingObserver acct = new TokenAccountingObserver();
        acct.onUsage("openai:gpt-4o", new Usage(1_200, 800)); // supervisor turn
        acct.onUsage("openai:gpt-4o", new Usage(900, 600)); // a second supervisor turn
        acct.onUsage("ollama:gemma4", new Usage(5_000, 2_500)); // local worker turns
        return acct;
    }

    /** Bring-your-own prices ($ per 1M tokens); the local model is free. */
    static Pricing pricing() {
        return new Pricing(Map.of(
                "openai:gpt-4o", new TokenPrice(2.50, 10.00),
                "ollama:gemma4", TokenPrice.FREE));
    }

    public static void main(String[] args) {
        TokenAccountingObserver acct = simulatedRun();
        Pricing pricing = pricing();
        Map<String, Usage> byModel = acct.tokensByModel();

        System.out.println("== ObservabilityDemo ==  per-model token accounting + bring-your-own cost\n");
        byModel.forEach((model, u) -> System.out.printf(
                "  %-16s in=%-6d out=%-6d  cost=$%.4f%n", model, u.inputTokens(), u.outputTokens(),
                pricing.cost(model, u)));
        long totalTokens = byModel.values().stream().mapToLong(Usage::totalTokens).sum();
        System.out.printf("%n  total: %d tokens   $%.4f%n", totalTokens, pricing.total(byModel));
        System.out.println("\nNo prices are bundled — set your own (list or negotiated). For tracing, add "
                + "OtelAgentObserver (agent-observability-otel) or MicrometerAgentObserver (the starter).");
    }
}
