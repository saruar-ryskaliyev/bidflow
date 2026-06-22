package io.bidflow.sim;

/**
 * How badly the simulated network behaves.
 *
 * @param minLatencyNanos lower bound on delivery delay
 * @param maxLatencyNanos upper bound on delivery delay; variable latency is what produces
 *     message reordering, so a range wider than the interval between sends will deliver
 *     messages out of order without any explicit reordering logic
 * @param dropProbability chance a message is silently lost
 * @param duplicateProbability chance a message is delivered twice, with independent latency
 *     for each copy. Duplication is not a curiosity: a retry whose original was merely slow
 *     rather than lost looks exactly like this, and any budget accounting that charges twice
 *     for one delivery is a financial bug.
 */
public record NetworkConditions(
        long minLatencyNanos, long maxLatencyNanos, double dropProbability, double duplicateProbability) {

    public NetworkConditions {
        if (minLatencyNanos < 0) {
            throw new IllegalArgumentException("minLatencyNanos must not be negative, was " + minLatencyNanos);
        }
        if (maxLatencyNanos < minLatencyNanos) {
            throw new IllegalArgumentException(
                    "maxLatencyNanos " + maxLatencyNanos + " is below minLatencyNanos " + minLatencyNanos);
        }
        requireProbability(dropProbability, "dropProbability");
        requireProbability(duplicateProbability, "duplicateProbability");
    }

    private static void requireProbability(double value, String name) {
        if (!(value >= 0.0 && value <= 1.0)) {
            throw new IllegalArgumentException(name + " must be in [0, 1], was " + value);
        }
    }

    /** Instant, lossless delivery. Useful only for isolating logic bugs from timing bugs. */
    public static NetworkConditions perfect() {
        return new NetworkConditions(0L, 0L, 0.0, 0.0);
    }

    /** Same-datacenter: a few hundred microseconds, essentially lossless. */
    public static NetworkConditions lan() {
        return new NetworkConditions(150_000L, 600_000L, 0.0001, 0.0);
    }

    /** Cross-region: tens of milliseconds, occasional loss, occasional duplicate. */
    public static NetworkConditions wan() {
        return new NetworkConditions(25_000_000L, 90_000_000L, 0.005, 0.001);
    }

    /**
     * Deliberately worse than production. Systems that only work on a good network are
     * systems whose failure modes have not been found yet.
     */
    public static NetworkConditions hostile() {
        return new NetworkConditions(1_000_000L, 500_000_000L, 0.1, 0.05);
    }
}
