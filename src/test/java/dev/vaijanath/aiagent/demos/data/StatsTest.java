package dev.vaijanath.aiagent.demos.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StatsTest {

    @Test
    void meanMedianAndStddev() {
        double[] xs = {2, 4, 4, 4, 5, 5, 7, 9};
        assertEquals(5.0, Stats.mean(xs), 1e-9);
        assertEquals(4.5, Stats.median(xs), 1e-9);
        assertEquals(2.138, Stats.stddev(xs), 1e-3); // sample stddev
    }

    @Test
    void percentilesInterpolate() {
        double[] xs = {1, 2, 3, 4};
        assertEquals(1.75, Stats.percentile(xs, 25), 1e-9);
        assertEquals(3.25, Stats.percentile(xs, 75), 1e-9);
    }

    @Test
    void perfectAndInversePearson() {
        double[] x = {1, 2, 3, 4, 5};
        double[] up = {2, 4, 6, 8, 10};
        double[] down = {10, 8, 6, 4, 2};
        assertEquals(1.0, Stats.pearson(x, up), 1e-9);
        assertEquals(-1.0, Stats.pearson(x, down), 1e-9);
    }

    @Test
    void iqrFencesBoundTheMiddle() {
        double[] xs = {1, 2, 3, 4, 5, 6, 7, 8, 100};
        double[] fences = Stats.iqrFences(xs);
        assertTrue(fences[1] < 100, "100 should be above the upper fence");
        assertTrue(100 > fences[1]);
    }
}
