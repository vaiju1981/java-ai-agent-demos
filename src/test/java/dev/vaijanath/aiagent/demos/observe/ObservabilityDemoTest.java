package dev.vaijanath.aiagent.demos.observe;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vaijanath.aiagent.model.Usage;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ObservabilityDemoTest {

    @Test
    void attributesTokensPerModelAndPricesThem() {
        Map<String, Usage> byModel = ObservabilityDemo.simulatedRun().tokensByModel();

        // gpt-4o usage accumulates across its two turns; the local model is separate.
        assertEquals(new Usage(2_100, 1_400), byModel.get("openai:gpt-4o"));
        assertEquals(new Usage(5_000, 2_500), byModel.get("ollama:gemma4"));

        // Cost: gpt-4o = 2100/1e6*2.50 + 1400/1e6*10.00 = 0.01925; gemma4 is free.
        assertEquals(0.01925, ObservabilityDemo.pricing().total(byModel), 1e-9);
    }
}
