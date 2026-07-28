package io.bidflow.load;

import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;

/**
 * Records completion latency from each call's <em>intended</em> start time, so a stall in
 * the client or server appears as a long sample rather than being omitted from the
 * histogram (coordinated-omission free).
 */
public final class IntendedStartLatencyRecorder {

    private final Recorder recorder;
    private final long highestTrackableNanos;

    public IntendedStartLatencyRecorder(long highestTrackableNanos) {
        if (highestTrackableNanos < 1_000_000L) {
            throw new IllegalArgumentException("highestTrackableNanos too small: " + highestTrackableNanos);
        }
        this.highestTrackableNanos = highestTrackableNanos;
        this.recorder = new Recorder(highestTrackableNanos, 3);
    }

    /** Records {@code System.nanoTime() - intendedStartNanos}, clamped to the trackable range. */
    public void recordCompletion(long intendedStartNanos) {
        long latency = System.nanoTime() - intendedStartNanos;
        if (latency < 0) {
            latency = 0;
        }
        if (latency > highestTrackableNanos) {
            latency = highestTrackableNanos;
        }
        recorder.recordValue(latency);
    }

    public Histogram snapshot() {
        return recorder.getIntervalHistogram();
    }

    public static String formatReport(Histogram histogram, String label) {
        final StringBuilder sb = new StringBuilder();
        sb.append(label).append('\n');
        sb.append(String.format(Locale.ROOT, "  count      %d%n", histogram.getTotalCount()));
        sb.append(String.format(Locale.ROOT, "  mean      %.3f ms%n", histogram.getMean() / 1_000_000.0));
        sb.append(String.format(Locale.ROOT, "  p50       %.3f ms%n", histogram.getValueAtPercentile(50) / 1_000_000.0));
        sb.append(String.format(Locale.ROOT, "  p90       %.3f ms%n", histogram.getValueAtPercentile(90) / 1_000_000.0));
        sb.append(String.format(Locale.ROOT, "  p99       %.3f ms%n", histogram.getValueAtPercentile(99) / 1_000_000.0));
        sb.append(String.format(Locale.ROOT, "  p99.9     %.3f ms%n", histogram.getValueAtPercentile(99.9) / 1_000_000.0));
        sb.append(String.format(Locale.ROOT, "  max       %.3f ms%n", histogram.getMaxValue() / 1_000_000.0));
        return sb.toString();
    }

    public static String toJson(Histogram histogram) {
        return String.format(
                Locale.ROOT,
                "{\"count\":%d,\"meanNanos\":%.3f,\"p50\":%d,\"p90\":%d,\"p99\":%d,\"p999\":%d,\"max\":%d}",
                histogram.getTotalCount(),
                histogram.getMean(),
                histogram.getValueAtPercentile(50),
                histogram.getValueAtPercentile(90),
                histogram.getValueAtPercentile(99),
                histogram.getValueAtPercentile(99.9),
                histogram.getMaxValue());
    }
}
