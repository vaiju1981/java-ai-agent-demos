package dev.vaijanath.aiagent.demos.multiagent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.vaijanath.aiagent.a2a.A2aServer;
import dev.vaijanath.aiagent.demos.multiagent.MultiAgentNewsroomDemo.Newsroom;
import dev.vaijanath.aiagent.model.StubModelPort;
import org.junit.jupiter.api.Test;

class MultiAgentNewsroomDemoTest {

    @Test
    void runsTheWholeGraphThroughA2aOnTheStub() throws Exception {
        StubModelPort model = new StubModelPort();
        try (A2aServer research =
                new A2aServer(MultiAgentNewsroomDemo.researcher(model), "research", "facts")) {

            Newsroom newsroom =
                    MultiAgentNewsroomDemo.newsroom(model, "http://localhost:" + research.port() + "/");
            String article = newsroom.write("virtual threads");

            // The whole pipeline ran end to end: A2A research → draft (agents-as-tools) → group-chat
            // review, looping on the conditional edge until the step budget, all behind the @AiService.
            assertNotNull(article);
            assertFalse(article.isBlank());
        }
    }

    @Test
    void mainRunsEndToEndOnTheStub() throws Exception {
        // Exercises the full entry point (it builds and closes its own A2aServer) on the stub — no
        // model and no key needed; completing without throwing is the assertion.
        MultiAgentNewsroomDemo.main(new String[] {"virtual threads"});
    }
}
