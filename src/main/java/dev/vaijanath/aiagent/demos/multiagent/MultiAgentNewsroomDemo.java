package dev.vaijanath.aiagent.demos.multiagent;

import dev.vaijanath.aiagent.a2a.A2aServer;
import dev.vaijanath.aiagent.a2a.RemoteAgent;
import dev.vaijanath.aiagent.agent.Agent;
import dev.vaijanath.aiagent.agent.AgentResponse;
import dev.vaijanath.aiagent.agent.Agents;
import dev.vaijanath.aiagent.agent.DefaultAgent;
import dev.vaijanath.aiagent.demos.Demos;
import dev.vaijanath.aiagent.graph.GraphAgent;
import dev.vaijanath.aiagent.model.ModelPort;
import dev.vaijanath.aiagent.service.AiServices;
import dev.vaijanath.aiagent.service.UserMessage;
import dev.vaijanath.aiagent.service.V;
import dev.vaijanath.aiagent.supervise.GroupChatAgent;
import dev.vaijanath.aiagent.supervise.RoundRobinSelector;

/**
 * One integrated showcase of the 0.3.0 multi-agent toolkit — every piece composing on the single
 * {@code Agent} seam. A "newsroom" is modelled as a workflow <b>graph</b> whose nodes are themselves
 * other orchestrators:
 *
 * <pre>
 *   topic ─▶ [research]  an A2A REMOTE agent (served on loopback, called like a local Agent)
 *           ─▶ [draft]   a writer whose specialists are AGENTS-AS-TOOLS
 *           ─▶ [review]  a GROUP CHAT panel (editor + fact-checker)
 *           ──▶ approved? END : back to [draft]      (a conditional EDGE → a revise cycle)
 * </pre>
 *
 * The whole graph is exposed behind a typed <b>{@code @AiService}</b> facade. Five 0.3.0 features in one
 * flow: {@code GraphAgent}, {@code agent-a2a}, {@code Agents.asTool}, {@code GroupChatAgent},
 * {@code AiServices}.
 *
 * <p>One model serves every role: set {@code AGENT_MODEL=gemma4:31b-cloud} (Ollama) for real output;
 * without it an honest stub runs the full wiring with placeholder text.
 *
 * <pre>{@code ./gradlew :demos:run -PmainClass=dev.vaijanath.aiagent.demos.multiagent.MultiAgentNewsroomDemo}</pre>
 */
public final class MultiAgentNewsroomDemo {

    private MultiAgentNewsroomDemo() {}

    /** A typed facade (an {@code @AiService}) over the whole composed graph. */
    public interface Newsroom {
        @UserMessage("{{topic}}")
        String write(@V("topic") String topic);
    }

    public static void main(String[] args) throws Exception {
        ModelPort model = Demos.modelFromEnv();
        String topic = args.length > 0 ? String.join(" ", args) : "virtual threads in Java";

        System.out.println(
                "== MultiAgentNewsroomDemo ==  graph · A2A · agents-as-tools · group chat · @AiService");
        System.out.println("model: " + model.name()
                + (Demos.isStub(model) ? "  (stub — set AGENT_MODEL=gemma4:31b-cloud for real output)" : ""));
        System.out.println("topic: " + topic + "\n");

        // The research desk runs as a SEPARATE service, reached over A2A — yet composes like a local agent.
        try (A2aServer researchService = new A2aServer(researcher(model), "research", "gathers key facts")) {
            Newsroom newsroom = newsroom(model, "http://localhost:" + researchService.port() + "/");
            String article = newsroom.write(topic);
            System.out.println("---- final article ----\n" + article);
        }
    }

    /** Wires the newsroom: a {@link GraphAgent} built from the four other patterns, behind an @AiService. */
    static Newsroom newsroom(ModelPort model, String researchEndpoint) {
        Agent research = new RemoteAgent(researchEndpoint); // A2A: a remote agent, used as a graph node
        Agent draft = writer(model); // agents-as-tools inside
        Agent review = reviewer(model); // group chat inside; preserves the article as the state

        Agent graph = GraphAgent.builder()
                .node("research", research)
                .node("draft", draft)
                .node("review", review)
                .start("research")
                .edge("research", "draft")
                .edge("draft", "review")
                // Conditional edge: the reviewer tags a draft "REVISE:" to send it back, else we publish.
                .edge("review", state -> state.startsWith("REVISE:") ? "draft" : GraphAgent.END)
                .maxSteps(6)
                .build();

        return AiServices.create(Newsroom.class, graph); // @AiService facade over the whole graph
    }

    /** The research agent, served remotely over A2A. */
    static Agent researcher(ModelPort model) {
        return DefaultAgent.builder()
                .model(model)
                .systemPrompt("You are a researcher. Given a topic, list three concise, factual bullet points.")
                .build();
    }

    /** The writer: a {@link DefaultAgent} whose specialist (a headline writer) is exposed as a tool. */
    static Agent writer(ModelPort model) {
        Agent headlineWriter = DefaultAgent.builder()
                .model(model)
                .systemPrompt("Write one short, catchy headline for the given notes. Reply with only the headline.")
                .build();
        return DefaultAgent.builder()
                .model(model)
                .systemPrompt("You are a journalist. Write a three-sentence article from the notes you are given. "
                        + "If the notes begin with 'REVISE:', rewrite the draft below to address the feedback. "
                        + "Call the 'headline' tool to title it, then put the headline on the first line.")
                .tool(Agents.asTool("headline", "writes a catchy headline for notes", headlineWriter))
                .maxSteps(4)
                .build();
    }

    /**
     * The review desk: a {@link GroupChatAgent} (editor + fact-checker) wrapped so the <i>article</i> —
     * not the verdict — stays the graph state. On approval the pristine draft flows on to become the final
     * output; otherwise it is tagged {@code "REVISE:"} (with the feedback) so the edge routes it back to the
     * writer.
     */
    static Agent reviewer(ModelPort model) {
        Agent editor = DefaultAgent.builder()
                .model(model)
                .systemPrompt("You are an editor. In one sentence, note the single most important improvement.")
                .build();
        Agent factChecker = DefaultAgent.builder()
                .model(model)
                .systemPrompt("You are a fact-checker. If the article is accurate and clear, reply with exactly "
                        + "the word APPROVED. Otherwise reply with one specific correction.")
                .build();
        // Round-robin with two participants → the fact-checker speaks last, so its verdict is the panel output.
        GroupChatAgent panel = GroupChatAgent.builder()
                .agent("editor", "suggests editorial improvements", editor)
                .agent("factChecker", "verifies accuracy; approves when correct", factChecker)
                .selector(new RoundRobinSelector())
                .maxRounds(2)
                .build();
        return request -> {
            String article = request.input();
            AgentResponse verdict = panel.run(request);
            return verdict.output().contains("APPROVED")
                    ? AgentResponse.completed(article) // forward the pristine article → it becomes the output
                    : AgentResponse.completed(
                            "REVISE: " + verdict.output() + "\n\n--- current draft ---\n" + article);
        };
    }
}
