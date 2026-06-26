package dev.vaijanath.aiagent.demos.finance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CategorizeMerchantToolTest {

    private final CategorizeMerchantTool tool = new CategorizeMerchantTool();

    @Test
    void classifiesKnownMerchant() {
        assertEquals("Dining", tool.invoke("{\"merchant\":\"Blue Bottle Coffee\"}").content());
        assertEquals("Travel", tool.invoke("{\"merchant\":\"United Airlines\"}").content());
    }

    @Test
    void fallsBackToOther() {
        assertEquals("Other", tool.invoke("{\"merchant\":\"Acme Widgets LLC\"}").content());
    }
}
