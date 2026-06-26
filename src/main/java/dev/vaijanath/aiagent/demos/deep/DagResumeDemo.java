package dev.vaijanath.aiagent.demos.deep;

import dev.vaijanath.aiagent.agent.Agent;
import dev.vaijanath.aiagent.agent.AgentRequest;
import dev.vaijanath.aiagent.agent.AgentResponse;
import dev.vaijanath.aiagent.agent.RequestContext;
import dev.vaijanath.aiagent.checkpoint.Checkpoint;
import dev.vaijanath.aiagent.checkpoint.CheckpointStore;
import dev.vaijanath.aiagent.deep.DeepAgent;
import dev.vaijanath.aiagent.deep.Plan;
import dev.vaijanath.aiagent.deep.PlanStep;
import dev.vaijanath.aiagent.deep.Planner;
import dev.vaijanath.aiagent.model.ModelPort;
import dev.vaijanath.aiagent.model.ModelResponse;
import dev.vaijanath.aiagent.sqlite.SqliteCheckpointStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A runnable, fully deterministic (no model required) walkthrough of two {@code DeepAgent} features:
 *
 * <ol>
 *   <li><b>DAG with data flow</b> — two independent subtasks feed a third that depends on both; the
 *       dependent step's input shows the upstream results injected into it.</li>
 *   <li><b>Crash-resumable orchestration</b> — the run is interrupted mid-plan; a fresh
 *       {@link SqliteCheckpointStore} over the same file (i.e. a process restart) reloads the saved
 *       progress, and a retry with the same {@code traceId} re-runs only the unfinished step.</li>
 * </ol>
 *
 * <p>Run it: {@code ./gradlew :demos:run -PmainClass=dev.vaijanath.aiagent.demos.deep.DagResumeDemo}
 */
public final class DagResumeDemo {

    private DagResumeDemo() {}

    private static final String TASK = "produce the Q2 board report";

    /** A demo-only stand-in for a process dying mid-step (an Error, so the worker loop won't catch it). */
    static final class SimulatedOutage extends Error {
        SimulatedOutage(String message) {
            super(message);
        }
    }

    /** What the walkthrough proved, surfaced for the smoke test. */
    public record Outcome(String finalAnswer, List<String> resumedSteps, String resumedStepInput,
            boolean checkpointClearedAfter) {
    }

    public static void main(String[] args) throws IOException {
        // A controlled, app-owned location (not the shared system temp dir), cleaned up afterwards.
        Path db = Path.of("build", "dag-resume-demo.db");
        Files.createDirectories(db.toAbsolutePath().getParent());
        Files.deleteIfExists(db);
        try {
            run(db, System.out::println);
        } finally {
            Files.deleteIfExists(db);
        }
    }

    public static Outcome run(Path db, Consumer<String> out) {
        // A fixed DAG: steps 1 and 2 are independent; step 3 depends on both and consumes their results.
        Planner planner = task -> new Plan(List.of(
                new PlanStep(1, "gather Q2 revenue"),
                new PlanStep(2, "gather Q2 headcount"),
                new PlanStep(3, "write the board summary", List.of(1, 2))));

        List<String> executed = new ArrayList<>();
        AtomicBoolean outage = new AtomicBoolean(true);
        Supplier<Agent> worker = () -> request -> {
            String input = request.input();
            String head = input.split("\n", 2)[0]; // the step's own instruction (first line)
            if (head.startsWith("write the board summary") && outage.get()) {
                throw new SimulatedOutage("worker node crashed mid-report");
            }
            executed.add(head);
            return AgentResponse.completed(switch (head) {
                case "gather Q2 revenue" -> "Q2 revenue was $4.2M";
                case "gather Q2 headcount" -> "Q2 headcount was 87";
                default -> "BOARD SUMMARY built from upstream context:\n  " + input.replace("\n", "\n  ");
            });
        };
        ModelPort synthesizer = request ->
                ModelResponse.text("Board report complete — all three subtasks synthesized.");
        RequestContext ctx = RequestContext.session("exec-report-2026-q2"); // stable traceId == resume key

        out.accept("== DeepAgent DAG + crash-resume demo ==");
        out.accept("plan: [1] revenue  [2] headcount  [3] summary (depends on 1,2)\n");

        // --- Attempt 1: crashes after the two independent steps complete and are checkpointed.
        out.accept("-- attempt 1 --");
        try {
            newDeepAgent(planner, worker, synthesizer, new SqliteCheckpointStore(db))
                    .run(new AgentRequest(TASK, ctx));
            out.accept("(unexpected: no crash)");
        } catch (SimulatedOutage e) {
            out.accept("💥 crash: " + e.getMessage() + "  (completed so far: " + executed + ")\n");
        }

        // --- The durable record, read by a brand-new store over the same file (i.e. after a restart).
        Checkpoint saved =
                new SqliteCheckpointStore(db).load(ctx.tenant(), ctx.traceId()).orElseThrow();
        out.accept("durable checkpoint on disk (" + db.getFileName() + "):");
        for (Checkpoint.Step step : saved.steps()) {
            out.accept("  step " + step.index() + " [" + step.status() + "] " + step.description());
        }
        out.accept("");

        // --- Attempt 2: a new agent + new store over the same file resumes from the checkpoint.
        out.accept("-- attempt 2 (resume) --");
        int before = executed.size();
        outage.set(false);
        DeepAgent resumed = newDeepAgent(planner, worker, synthesizer, new SqliteCheckpointStore(db));
        AgentResponse response = resumed.run(new AgentRequest(TASK, ctx));
        List<String> resumedSteps = List.copyOf(executed.subList(before, executed.size()));

        out.accept("re-ran only: " + resumedSteps + "  (steps 1 & 2 were restored, not recomputed)");
        String resumedStepInput = resumed.workspace().read("step-3.txt").orElse("");
        out.accept("step 3's output (note the restored upstream data):");
        out.accept("  " + resumedStepInput.replace("\n", "\n  "));
        out.accept("\nfinal answer: " + response.output());

        boolean cleared = new SqliteCheckpointStore(db).load(ctx.tenant(), ctx.traceId()).isEmpty();
        out.accept("checkpoint after clean completion: " + (cleared ? "deleted" : "STILL PRESENT"));

        return new Outcome(response.output(), resumedSteps, resumedStepInput, cleared);
    }

    private static DeepAgent newDeepAgent(
            Planner planner, Supplier<Agent> worker, ModelPort synthesizer, CheckpointStore checkpoints) {
        return DeepAgent.builder()
                .planner(planner)
                .worker(worker)
                .synthesizer(synthesizer)
                .parallel(false) // sequential so the crash propagates and the narration stays ordered
                .checkpoints(checkpoints)
                .build();
    }
}
