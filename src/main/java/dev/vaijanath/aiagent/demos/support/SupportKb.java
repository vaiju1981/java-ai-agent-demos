package dev.vaijanath.aiagent.demos.support;

import dev.vaijanath.aiagent.demos.Demos;
import dev.vaijanath.aiagent.langchain4j.OllamaModelPorts;
import dev.vaijanath.aiagent.model.ModelPort;
import dev.vaijanath.aiagent.rag.Embedder;
import dev.vaijanath.aiagent.rag.InMemoryVectorStore;
import java.util.Locale;
import java.util.Map;

/**
 * Northwind Outfitters' support knowledge base — the product/policy corpus the copilot is grounded in.
 * Answers are <b>retrieved from these articles, not invented</b>: {@code RetrievalAugmentedAgent}
 * embeds the customer's question, pulls the most similar articles from the vector store, and weaves
 * them into the prompt.
 *
 * <p>The same {@link Embedder} powers both this KB and the {@code JdbcEpisodicStore} of past
 * resolutions. With {@code AGENT_EMBEDDING_MODEL} set it is a real Ollama embedder (true semantics);
 * otherwise it falls back to a deterministic keyword-hashing embedder so the wiring runs offline.
 */
public final class SupportKb {

    /** The store/tenant the KB and episodic memory are scoped to (recall never crosses tenants). */
    public static final String TENANT = "northwind";

    private static final Map<String, String> ARTICLES = Map.ofEntries(
            Map.entry("refund-policy",
                    "Refund policy: you may return most items within 30 days of delivery for a full "
                            + "refund to your original payment method. The 30-day window starts on the "
                            + "delivery date. Items must be unused with proof of purchase."),
            Map.entry("returns-rma",
                    "How to return an item: start a return from Your Orders to get an RMA number and a "
                            + "prepaid shipping label. Pack the item, attach the label, and drop it at any "
                            + "carrier location. Refunds are issued 3-5 business days after we receive it."),
            Map.entry("shipping",
                    "Shipping: standard shipping takes 5-7 business days; express shipping is free on "
                            + "orders over $50. You can track a shipment from Your Orders once it leaves "
                            + "our warehouse."),
            Map.entry("warranty",
                    "Warranty: outdoor gear carries a 1-year limited warranty against manufacturing "
                            + "defects (failed seams, broken zippers, delamination from normal use). The "
                            + "warranty does NOT cover accidental damage, water damage from misuse, normal "
                            + "wear, or loss. Warranty claims are resolved by repair or replacement, not a "
                            + "cash refund."),
            Map.entry("damaged-on-arrival",
                    "Damaged or wrong item on arrival: report it within 48 hours of delivery with a photo "
                            + "and we will send a free replacement or a full refund — no return shipping "
                            + "needed."),
            Map.entry("billing-account",
                    "Billing and account: update your saved card and view past invoices under Account > "
                            + "Billing. Subscription orders can be paused or cancelled before the next ship "
                            + "date with no fee."));

    private SupportKb() {}

    /** Builds the vector store and ingests every KB article under the {@link #TENANT}. */
    public static InMemoryVectorStore vectorStore(Embedder embedder) {
        InMemoryVectorStore store = new InMemoryVectorStore(embedder);
        ARTICLES.forEach((id, text) -> store.add(TENANT, id, text));
        return store;
    }

    /** A real Ollama embedder if {@code AGENT_EMBEDDING_MODEL} is set, else a deterministic fallback. */
    public static Embedder embedder(ModelPort model) {
        String embeddingModel = System.getenv("AGENT_EMBEDDING_MODEL");
        if (!Demos.isStub(model) && embeddingModel != null && !embeddingModel.isBlank()) {
            String baseUrl = System.getenv().getOrDefault("OLLAMA_BASE_URL", "http://localhost:11434");
            return OllamaModelPorts.ollamaEmbedder(baseUrl, embeddingModel);
        }
        return SupportKb::keywordHash; // deterministic offline fallback (keyword overlap, not semantics)
    }

    private static float[] keywordHash(String text) {
        float[] v = new float[96];
        for (String token : text.toLowerCase(Locale.ROOT).split("\\W+")) {
            if (token.length() >= 3) {
                v[Math.floorMod(token.hashCode(), v.length)] += 1f;
            }
        }
        return v;
    }
}
