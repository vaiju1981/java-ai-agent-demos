package dev.vaijanath.aiagent.demos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vaijanath.aiagent.tool.Tool;
import dev.vaijanath.aiagent.tool.ToolEffect;
import dev.vaijanath.aiagent.tool.ToolResult;
import dev.vaijanath.aiagent.tool.ToolSpec;
import java.util.function.Function;

/** A compact {@link Tool} built from a name, description, JSON schema, and a pure function — so a
 *  large toolkit of small calculators/lookups can be defined inline. Pure, so {@link ToolEffect#READ_ONLY}. */
public final class SimpleTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String name;
    private final String description;
    private final String schema;
    private final Function<JsonNode, String> fn;

    public SimpleTool(String name, String description, String schema, Function<JsonNode, String> fn) {
        this.name = name;
        this.description = description;
        this.schema = schema;
        this.fn = fn;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(name, description, schema, ToolEffect.READ_ONLY);
    }

    @Override
    public ToolResult invoke(String argumentsJson) {
        try {
            return ToolResult.ok(fn.apply(MAPPER.readTree(argumentsJson)));
        } catch (Exception e) {
            return ToolResult.error(name + " failed: " + e.getMessage());
        }
    }

    /** Builds an object schema with the given number properties, all required. */
    public static String numbers(String... names) {
        StringBuilder props = new StringBuilder();
        StringBuilder required = new StringBuilder();
        for (int i = 0; i < names.length; i++) {
            if (i > 0) {
                props.append(",");
                required.append(",");
            }
            props.append("\"").append(names[i]).append("\":{\"type\":\"number\"}");
            required.append("\"").append(names[i]).append("\"");
        }
        return "{\"type\":\"object\",\"properties\":{" + props + "},\"required\":[" + required + "]}";
    }
}
