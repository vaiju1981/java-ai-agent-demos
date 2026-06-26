package dev.vaijanath.aiagent.demos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vaijanath.aiagent.tool.Tool;
import dev.vaijanath.aiagent.tool.ToolEffect;
import dev.vaijanath.aiagent.tool.ToolResult;
import dev.vaijanath.aiagent.tool.ToolSpec;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A persisted report the agent builds up across steps: it calls {@code record_finding} as it
 * discovers each result, and the demo renders the assembled markdown report at the end — so a deep
 * investigation produces a durable, structured deliverable, not just a final chat message.
 *
 * <p>It is an internal scratchpad (no external side effect), so it is {@link ToolEffect#READ_ONLY}
 * and runs under the default deny-by-default policy without needing an allow-list.
 */
public final class ReportTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String title;
    private final Map<String, String> sections = new LinkedHashMap<>();

    public ReportTool(String title) {
        this.title = title;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "record_finding",
                "Record one finding in the report under a short section title, then keep investigating. "
                        + "Call this for each concrete result you establish.",
                "{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\"},"
                        + "\"finding\":{\"type\":\"string\"}},\"required\":[\"title\",\"finding\"]}",
                ToolEffect.READ_ONLY);
    }

    @Override
    public synchronized ToolResult invoke(String argumentsJson) {
        try {
            JsonNode a = MAPPER.readTree(argumentsJson);
            String section = a.path("title").asText("").strip();
            String finding = a.path("finding").asText("").strip();
            if (section.isEmpty() || finding.isEmpty()) {
                return ToolResult.error("both 'title' and 'finding' are required");
            }
            sections.merge(section, finding, (existing, add) -> existing + "\n" + add);
            return ToolResult.ok("recorded under '" + section + "' (" + sections.size() + " sections so far)");
        } catch (Exception e) {
            return ToolResult.error("record_finding failed: " + e.getMessage());
        }
    }

    public synchronized String render() {
        StringBuilder sb = new StringBuilder("# ").append(title).append("\n\n");
        if (sections.isEmpty()) {
            sb.append("_(no findings recorded)_");
        }
        for (Map.Entry<String, String> e : sections.entrySet()) {
            sb.append("## ").append(e.getKey()).append('\n').append(e.getValue()).append("\n\n");
        }
        return sb.toString().strip();
    }

    public synchronized int sectionCount() {
        return sections.size();
    }
}
