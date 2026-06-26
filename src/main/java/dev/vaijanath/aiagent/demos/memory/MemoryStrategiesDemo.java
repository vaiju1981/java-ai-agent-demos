package dev.vaijanath.aiagent.demos.memory;

import dev.vaijanath.aiagent.memory.Memory;
import dev.vaijanath.aiagent.memory.TokenWindowedMemory;
import dev.vaijanath.aiagent.model.HeuristicTokenizer;
import dev.vaijanath.aiagent.model.Message;
import dev.vaijanath.aiagent.model.Tokenizer;
import java.util.List;

/**
 * Bounding context as a conversation grows. {@link TokenWindowedMemory} keeps the system message plus the
 * most recent turns that fit a token budget, dropping the oldest — so the prompt never blows past the
 * model's window. (For compression instead of dropping, {@code SummarizingMemory} summarizes older turns
 * with a model; shown in the cookbook.)
 *
 * <p>Deterministic — no model needed:
 * <pre>{@code
 * ./gradlew run -PmainClass=dev.vaijanath.aiagent.demos.memory.MemoryStrategiesDemo
 * }</pre>
 */
public final class MemoryStrategiesDemo {

    private MemoryStrategiesDemo() {}

    static Memory windowedAfter(int turns, int tokenBudget) {
        Tokenizer tokenizer = new HeuristicTokenizer();
        Memory windowed = new TokenWindowedMemory(tokenizer, tokenBudget);
        windowed.add(Message.system("You are a concise travel assistant."));
        for (int i = 1; i <= turns; i++) {
            windowed.add(Message.user("Turn " + i + ": what's a must-see in city number " + i + "?"));
            windowed.add(Message.assistant(
                    "In city " + i + ", don't miss the old town square and the riverside walk at dusk."));
        }
        return windowed;
    }

    public static void main(String[] args) {
        int turns = 12;
        int added = 1 + turns * 2;
        Memory windowed = windowedAfter(turns, 160); // a deliberately small budget so eviction is visible
        List<Message> kept = windowed.history();

        System.out.printf("Added %d messages across %d turns.%n", added, turns);
        System.out.printf("Token-windowed memory keeps %d: the system message + the most recent turns "
                + "that fit the 160-token budget.%n", kept.size());
        System.out.println("  first kept: " + kept.get(0).content());
        System.out.println("  last kept:  " + kept.get(kept.size() - 1).content());
        System.out.println("\nSummarizingMemory(tokenizer, summarizer, budget, keepRecent) compresses the "
                + "dropped turns with a model instead of discarding them.");
    }
}
