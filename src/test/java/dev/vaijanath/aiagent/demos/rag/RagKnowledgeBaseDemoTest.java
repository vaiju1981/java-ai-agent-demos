package dev.vaijanath.aiagent.demos.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.vaijanath.aiagent.rag.InMemoryVectorStore;
import dev.vaijanath.aiagent.rag.RetrievedChunk;
import java.util.List;
import org.junit.jupiter.api.Test;

class RagKnowledgeBaseDemoTest {

    @Test
    void retrievesTheRelevantPolicyForAQuestion() {
        InMemoryVectorStore kb = RagKnowledgeBaseDemo.knowledgeBase(RagKnowledgeBaseDemo::keywordHash);

        List<RetrievedChunk> hits = kb.retrieve("default", "What is the refund window?", 2);

        assertFalse(hits.isEmpty(), "the corpus should surface a relevant policy");
        assertEquals("refund", hits.get(0).id(), "the refund policy ranks first for a refund question");
    }
}
