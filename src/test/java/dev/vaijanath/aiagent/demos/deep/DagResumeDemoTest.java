package dev.vaijanath.aiagent.demos.deep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DagResumeDemoTest {

    @TempDir
    Path dir;

    @Test
    void resumesAfterCrashRerunningOnlyTheUnfinishedStepWithUpstreamData() {
        DagResumeDemo.Outcome outcome = DagResumeDemo.run(dir.resolve("ckpt.db"), line -> { });

        // Only step 3 re-ran on resume; steps 1 & 2 were restored from the checkpoint, not recomputed.
        assertEquals(List.of("write the board summary"), outcome.resumedSteps());
        // The resumed step received the restored upstream results (DAG data flow survived the crash).
        assertTrue(outcome.resumedStepInput().contains("Q2 revenue was $4.2M"), outcome.resumedStepInput());
        assertTrue(outcome.resumedStepInput().contains("Q2 headcount was 87"), outcome.resumedStepInput());
        assertFalse(outcome.finalAnswer().isBlank());
        assertTrue(outcome.checkpointClearedAfter()); // cleaned up after a clean completion
    }
}
