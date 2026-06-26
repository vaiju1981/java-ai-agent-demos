package dev.vaijanath.aiagent.demos.multimodal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vaijanath.aiagent.model.Media;
import dev.vaijanath.aiagent.model.Message;
import org.junit.jupiter.api.Test;

class MultimodalDemoTest {

    @Test
    void userTurnCarriesOneImage() {
        Message turn = MultimodalDemo.visionTurn();

        assertEquals(1, turn.media().size());
        assertEquals(Media.Kind.IMAGE, turn.media().get(0).kind());
        assertEquals("image/png", turn.media().get(0).mimeType());
    }

    @Test
    void rendersARealPng() {
        byte[] png = MultimodalDemo.barChartPng();

        assertTrue(png.length > 100, "a real PNG, not empty");
        assertEquals((byte) 0x89, png[0]); // PNG signature
        assertEquals('P', png[1]);
        assertEquals('N', png[2]);
        assertEquals('G', png[3]);
    }
}
