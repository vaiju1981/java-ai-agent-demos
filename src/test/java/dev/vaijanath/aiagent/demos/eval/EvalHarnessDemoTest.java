package dev.vaijanath.aiagent.demos.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vaijanath.aiagent.agent.Agent;
import dev.vaijanath.aiagent.agent.AgentResponse;
import dev.vaijanath.aiagent.eval.EvalReport;
import dev.vaijanath.aiagent.eval.Evaluator;
import org.junit.jupiter.api.Test;

class EvalHarnessDemoTest {

    @Test
    void scoresAnAgentAgainstTheSuite() {
        // A deterministic stand-in agent that answers each case correctly — exercises the harness itself.
        Agent perfect = req -> AgentResponse.completed("the result is "
                + (req.input().contains("12 plus 30")
                        ? "42"
                        : req.input().contains("7 times 8") ? "56" : "99"));

        EvalReport report = Evaluator.run(perfect, EvalHarnessDemo.suite());

        assertEquals(3, report.total());
        assertEquals(3, report.passed());
        assertEquals(1.0, report.passRate());
    }
}
