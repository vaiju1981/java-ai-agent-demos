package dev.vaijanath.aiagent.demos;

import dev.vaijanath.aiagent.langchain4j.OllamaModelPorts;
import dev.vaijanath.aiagent.model.ModelPort;
import dev.vaijanath.aiagent.model.StubModelPort;

/** Shared helpers for the demos. */
public final class Demos {

    private Demos() {}

    /** A real Ollama model if {@code AGENT_MODEL} is set, else an honest stub. */
    public static ModelPort modelFromEnv() {
        String modelName = System.getenv("AGENT_MODEL");
        if (modelName == null || modelName.isBlank()) {
            return new StubModelPort();
        }
        String baseUrl = System.getenv().getOrDefault("OLLAMA_BASE_URL", "http://localhost:11434");
        return OllamaModelPorts.ollama(baseUrl, modelName);
    }

    public static boolean isStub(ModelPort port) {
        return port instanceof StubModelPort;
    }
}
