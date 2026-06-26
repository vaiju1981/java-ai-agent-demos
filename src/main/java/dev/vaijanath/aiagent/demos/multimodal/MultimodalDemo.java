package dev.vaijanath.aiagent.demos.multimodal;

import dev.vaijanath.aiagent.demos.Demos;
import dev.vaijanath.aiagent.model.Media;
import dev.vaijanath.aiagent.model.Message;
import dev.vaijanath.aiagent.model.ModelPort;
import dev.vaijanath.aiagent.model.ModelRequest;
import dev.vaijanath.aiagent.model.ModelResponse;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Multimodal input: attach an image to a user turn with {@link Message#user(String, List)} and send it to
 * a <b>vision model</b>. Multimodal is a model-level capability, so this calls the {@link ModelPort}
 * directly; text-only models simply ignore the media.
 *
 * <p>The image is generated in-process (a tiny bar chart), so the demo is self-contained. Point
 * {@code AGENT_MODEL} at a vision model (e.g. {@code llava}) to actually describe it:
 *
 * <pre>{@code
 * AGENT_MODEL=llava ./gradlew run -PmainClass=dev.vaijanath.aiagent.demos.multimodal.MultimodalDemo
 * }</pre>
 */
public final class MultimodalDemo {

    private MultimodalDemo() {}

    /** A user turn carrying a generated bar-chart image for a vision model to describe. */
    static Message visionTurn() {
        return Message.user(
                "Describe this chart in one sentence — which bar is tallest?",
                List.of(Media.image("image/png", barChartPng())));
    }

    public static void main(String[] args) {
        ModelPort model = Demos.modelFromEnv();
        System.out.println("== MultimodalDemo ==  model: " + model.name());

        ModelResponse answer = model.chat(ModelRequest.of(List.of(visionTurn())));
        System.out.println(answer.text());

        if (Demos.isStub(model)) {
            System.out.println("\n(stub model — set AGENT_MODEL to a vision model like 'llava' to read the image)");
        }
    }

    /** A small self-contained PNG: three bars of increasing height, so there's something to describe. */
    static byte[] barChartPng() {
        int w = 240;
        int h = 160;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(0x3B82F6));
        int[] heights = {40, 80, 130};
        for (int i = 0; i < heights.length; i++) {
            g.fillRect(30 + i * 70, h - heights[i] - 10, 50, heights[i]);
        }
        g.dispose();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to render the chart image", e);
        }
    }
}
