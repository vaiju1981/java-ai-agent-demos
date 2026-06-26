package dev.vaijanath.aiagent.demos.memory;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vaijanath.aiagent.model.Message;
import java.util.List;
import org.junit.jupiter.api.Test;

class MemoryStrategiesDemoTest {

    @Test
    void windowingBoundsHistoryButKeepsTheSystemMessage() {
        int turns = 12;
        int added = 1 + turns * 2;

        List<Message> kept = MemoryStrategiesDemo.windowedAfter(turns, 160).history();

        assertTrue(kept.size() < added, "the window must drop old turns once the budget is exceeded");
        assertTrue(
                kept.stream().anyMatch(m -> m.content().contains("travel assistant")),
                "the system message is pinned, never evicted");
    }
}
