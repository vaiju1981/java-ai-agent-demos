package dev.vaijanath.aiagent.demos.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vaijanath.aiagent.tool.Tool;
import dev.vaijanath.aiagent.tool.ToolEffect;
import dev.vaijanath.aiagent.tool.ToolResult;
import dev.vaijanath.aiagent.tool.ToolSpec;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Categorizes a merchant name into a spending category by keyword (a deterministic rules tool). */
public final class CategorizeMerchantTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** keyword → category (checked in order). */
    private static final Map<String, String> RULES = new LinkedHashMap<>();

    static {
        RULES.put("coffee", "Dining");
        RULES.put("cafe", "Dining");
        RULES.put("restaurant", "Dining");
        RULES.put("grill", "Dining");
        RULES.put("market", "Groceries");
        RULES.put("grocery", "Groceries");
        RULES.put("foods", "Groceries");
        RULES.put("gas", "Transport");
        RULES.put("fuel", "Transport");
        RULES.put("uber", "Transport");
        RULES.put("lyft", "Transport");
        RULES.put("air", "Travel");
        RULES.put("hotel", "Travel");
        RULES.put("inn", "Travel");
        RULES.put("netflix", "Entertainment");
        RULES.put("spotify", "Entertainment");
        RULES.put("cinema", "Entertainment");
        RULES.put("electric", "Utilities");
        RULES.put("energy", "Utilities");
        RULES.put("mobile", "Utilities");
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "categorize_merchant",
                "Classify a merchant name into a spending category (Dining, Groceries, Transport, "
                        + "Travel, Entertainment, Utilities, or Other).",
                "{\"type\":\"object\",\"properties\":{\"merchant\":{\"type\":\"string\"}},"
                        + "\"required\":[\"merchant\"]}",
                ToolEffect.READ_ONLY);
    }

    @Override
    public ToolResult invoke(String argumentsJson) {
        String merchant;
        try {
            merchant = MAPPER.readTree(argumentsJson).path("merchant").asText("");
        } catch (Exception e) {
            return ToolResult.error("could not parse arguments: " + argumentsJson);
        }
        String m = merchant.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> rule : RULES.entrySet()) {
            if (m.contains(rule.getKey())) {
                return ToolResult.ok(rule.getValue());
            }
        }
        return ToolResult.ok("Other");
    }
}
