package dev.vaijanath.aiagent.demos;

import dev.vaijanath.aiagent.langchain4j.OllamaModelPorts;
import dev.vaijanath.aiagent.model.ModelPort;
import dev.vaijanath.aiagent.model.StubModelPort;
import dev.vaijanath.aiagent.tool.Tool;

/** Shared helpers for the demos. */
public final class Demos {

    private Demos() {}

    /** A small calculator tool shared by the skill and eval demos: {@code a <op> b}. */
    public static Tool mathTool() {
        String schema = "{\"type\":\"object\",\"properties\":{"
                + "\"a\":{\"type\":\"number\"},\"b\":{\"type\":\"number\"},"
                + "\"op\":{\"type\":\"string\",\"enum\":[\"add\",\"subtract\",\"multiply\",\"divide\"]}},"
                + "\"required\":[\"a\",\"b\",\"op\"]}";
        return new SimpleTool("math", "compute a <op> b (op: add, subtract, multiply, divide)", schema, args -> {
            double a = args.get("a").asDouble();
            double b = args.get("b").asDouble();
            double r = switch (args.get("op").asText()) {
                case "add" -> a + b;
                case "subtract" -> a - b;
                case "multiply" -> a * b;
                case "divide" -> a / b;
                default -> Double.NaN;
            };
            return (r == Math.rint(r) && Double.isFinite(r)) ? String.valueOf((long) r) : String.valueOf(r);
        });
    }

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
