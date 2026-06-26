package dev.vaijanath.aiagent.demos.eval;

import dev.vaijanath.aiagent.agent.Agent;
import dev.vaijanath.aiagent.agent.DefaultAgent;
import dev.vaijanath.aiagent.demos.Demos;
import dev.vaijanath.aiagent.eval.EvalCase;
import dev.vaijanath.aiagent.eval.EvalReport;
import dev.vaijanath.aiagent.eval.Evaluator;
import dev.vaijanath.aiagent.model.ModelPort;
import java.util.List;

/**
 * Evaluation: run an agent against a suite of {@link EvalCase}s and print a pass-rate report — the way you
 * catch regressions in a tool-using agent. Each case checks the final answer contains the expected result.
 *
 * <p>Set {@code AGENT_MODEL} so the agent can actually call the math tool and reach the right number; with
 * a stub it scores 0 (honestly — the stub can't compute).
 *
 * <pre>{@code
 * AGENT_MODEL=gemma4:31b-cloud ./gradlew run -PmainClass=dev.vaijanath.aiagent.demos.eval.EvalHarnessDemo
 * }</pre>
 */
public final class EvalHarnessDemo {

    private EvalHarnessDemo() {}

    /** A tiny arithmetic suite: the agent must use the math tool and state the right number. */
    static List<EvalCase> suite() {
        return List.of(
                EvalCase.contains("add", "What is 12 plus 30? Use the math tool.", "42"),
                EvalCase.contains("multiply", "What is 7 times 8? Use the math tool.", "56"),
                EvalCase.contains("subtract", "What is 100 minus 1? Use the math tool.", "99"));
    }

    public static void main(String[] args) {
        ModelPort model = Demos.modelFromEnv();
        Agent agent = DefaultAgent.builder()
                .model(model)
                .systemPrompt("Use the math tool for arithmetic, then state the final number.")
                .tool(Demos.mathTool())
                .maxSteps(6)
                .build();

        EvalReport report = Evaluator.run(agent, suite());
        System.out.println("== EvalHarnessDemo ==  model: " + model.name() + "\n");
        System.out.print(report.render());
        if (Demos.isStub(model)) {
            System.out.println("\n(stub model — set AGENT_MODEL so the agent can use the math tool and pass)");
        }
    }
}
