package dev.vaijanath.aiagent.demos.rag;

import dev.vaijanath.aiagent.agent.Agent;
import dev.vaijanath.aiagent.agent.AgentRequest;
import dev.vaijanath.aiagent.demos.Demos;
import dev.vaijanath.aiagent.demos.Governed;
import dev.vaijanath.aiagent.langchain4j.OllamaModelPorts;
import dev.vaijanath.aiagent.model.ModelPort;
import dev.vaijanath.aiagent.rag.Embedder;
import dev.vaijanath.aiagent.rag.InMemoryVectorStore;
import dev.vaijanath.aiagent.rag.RetrievalAugmentedAgent;
import dev.vaijanath.aiagent.rag.RetrievedChunk;
import java.util.List;
import java.util.Locale;

/**
 * Retrieval-augmented answers: a small policy knowledge base is embedded into an
 * {@link InMemoryVectorStore}; {@link RetrievalAugmentedAgent} retrieves the top matches and weaves them
 * into the prompt so the model answers <b>from the corpus, not its memory</b>.
 *
 * <p>Set {@code AGENT_MODEL} (chat) and {@code AGENT_EMBEDDING_MODEL} (e.g. {@code nomic-embed-text}) for a
 * real, <i>semantic</i> run. With neither, it falls back to a deterministic keyword-hashing embedder so the
 * retrieval wiring still runs offline (keyword overlap rather than true semantics).
 *
 * <pre>{@code
 * AGENT_MODEL=gemma4:31b-cloud AGENT_EMBEDDING_MODEL=nomic-embed-text \
 *   ./gradlew run -PmainClass=dev.vaijanath.aiagent.demos.rag.RagKnowledgeBaseDemo
 * }</pre>
 */
public final class RagKnowledgeBaseDemo {

    private RagKnowledgeBaseDemo() {}

    /** The fictional store's policy corpus — each entry becomes one retrievable chunk. */
    static InMemoryVectorStore knowledgeBase(Embedder embedder) {
        InMemoryVectorStore kb = new InMemoryVectorStore(embedder);
        kb.add("default", "refund", "The refund window is 30 days from delivery, with the receipt.");
        kb.add("default", "shipping", "Standard shipping takes 5-7 business days; express shipping is free over $50.");
        kb.add("default", "warranty", "Electronics include a 1-year limited warranty against manufacturing defects.");
        kb.add("default", "software", "Opened software is non-refundable unless it is defective.");
        return kb;
    }

    public static void main(String[] args) {
        ModelPort model = Demos.modelFromEnv();
        Embedder embedder = embedder(model);
        InMemoryVectorStore kb = knowledgeBase(embedder);

        String question = "What is the refund window?";

        // 1. The retrieval step on its own — the evidence RAG grounds the answer in.
        System.out.println("Retrieved for: \"" + question + "\"");
        for (RetrievedChunk hit : kb.retrieve("default", question, 2)) {
            System.out.printf("  [%.3f] %s%n", hit.score(), hit.text());
        }

        // 2. The grounded answer: the agent only sees the retrieved context.
        Agent base = Governed.agent(
                        model, List.of(), "Answer only from the provided context; if it isn't there, say so.")
                .agent();
        Agent grounded = new RetrievalAugmentedAgent(base, kb);
        System.out.println("\nAnswer: " + grounded.run(new AgentRequest(question)).output());

        if (Demos.isStub(model)) {
            System.out.println("\n(stub model — set AGENT_MODEL + AGENT_EMBEDDING_MODEL for a real semantic run)");
        }
    }

    /** Real Ollama embeddings when configured; otherwise a deterministic keyword-hashing embedder. */
    private static Embedder embedder(ModelPort model) {
        String embeddingModel = System.getenv("AGENT_EMBEDDING_MODEL");
        if (!Demos.isStub(model) && embeddingModel != null && !embeddingModel.isBlank()) {
            String baseUrl = System.getenv().getOrDefault("OLLAMA_BASE_URL", "http://localhost:11434");
            return OllamaModelPorts.ollamaEmbedder(baseUrl, embeddingModel);
        }
        return RagKnowledgeBaseDemo::keywordHash;
    }

    /** A tiny deterministic embedder: hash each word into a fixed-width vector (keyword overlap, offline). */
    static float[] keywordHash(String text) {
        float[] v = new float[64];
        for (String token : text.toLowerCase(Locale.ROOT).split("\\W+")) {
            if (token.length() >= 3) {
                v[Math.floorMod(token.hashCode(), v.length)] += 1f;
            }
        }
        return v;
    }
}
