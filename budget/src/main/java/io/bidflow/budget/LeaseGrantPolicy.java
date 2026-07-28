package io.bidflow.budget;

/**
 * Caps how much of a requested lease face value the authority may grant right now.
 *
 * <p>Pacing is built on the lease mechanism rather than beside it: the serving path still
 * only consults a local wallet, and this policy decides how much new authority to hand out
 * when a shard asks.
 */
@FunctionalInterface
public interface LeaseGrantPolicy {

    /**
     * @param requestedMicros what the shard asked for
     * @param observedSpendMicros settled spend plus the latest reported spend on live leases
     * @param nowNanos the authority's clock
     * @return a grant cap in {@code [0, requestedMicros]}
     */
    long capWantedMicros(long requestedMicros, long observedSpendMicros, long nowNanos);
}
