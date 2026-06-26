package dev.vaijanath.aiagent.demos.data;

import java.util.Arrays;

/**
 * Small numeric statistics the analytics tools need but SQLite doesn't provide natively — median,
 * percentiles, sample standard deviation, Pearson correlation, and IQR outlier bounds. Kept pure and
 * dependency-free so it is straightforward to unit-test against known inputs.
 */
final class Stats {

    private Stats() {}

    static double mean(double[] xs) {
        if (xs.length == 0) {
            return Double.NaN;
        }
        double sum = 0;
        for (double x : xs) {
            sum += x;
        }
        return sum / xs.length;
    }

    /** Sample standard deviation (n-1 denominator). NaN for fewer than two points. */
    static double stddev(double[] xs) {
        if (xs.length < 2) {
            return Double.NaN;
        }
        double m = mean(xs);
        double ss = 0;
        for (double x : xs) {
            ss += (x - m) * (x - m);
        }
        return Math.sqrt(ss / (xs.length - 1));
    }

    /** The linear-interpolated percentile (p in [0,100]) of the values. */
    static double percentile(double[] xs, double p) {
        if (xs.length == 0) {
            return Double.NaN;
        }
        double[] sorted = xs.clone();
        Arrays.sort(sorted);
        if (sorted.length == 1) {
            return sorted[0];
        }
        double rank = (p / 100.0) * (sorted.length - 1);
        int lo = (int) Math.floor(rank);
        int hi = (int) Math.ceil(rank);
        if (lo == hi) {
            return sorted[lo];
        }
        double frac = rank - lo;
        return sorted[lo] * (1 - frac) + sorted[hi] * frac;
    }

    static double median(double[] xs) {
        return percentile(xs, 50);
    }

    /** Pearson correlation coefficient of two equal-length series; NaN if undefined. */
    static double pearson(double[] xs, double[] ys) {
        int n = Math.min(xs.length, ys.length);
        if (n < 2) {
            return Double.NaN;
        }
        double mx = mean(Arrays.copyOf(xs, n));
        double my = mean(Arrays.copyOf(ys, n));
        double sxy = 0;
        double sxx = 0;
        double syy = 0;
        for (int i = 0; i < n; i++) {
            double dx = xs[i] - mx;
            double dy = ys[i] - my;
            sxy += dx * dy;
            sxx += dx * dx;
            syy += dy * dy;
        }
        double denom = Math.sqrt(sxx * syy);
        return denom == 0 ? Double.NaN : sxy / denom;
    }

    /** Tukey outlier fences: values below {@code lower} or above {@code upper} are outliers. */
    static double[] iqrFences(double[] xs) {
        double q1 = percentile(xs, 25);
        double q3 = percentile(xs, 75);
        double iqr = q3 - q1;
        return new double[] {q1 - 1.5 * iqr, q3 + 1.5 * iqr};
    }

    static double round(double value, int decimals) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return value;
        }
        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }
}
