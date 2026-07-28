package io.bidflow.budget;

/**
 * The historical default: grant whatever headroom allows, without regard to the clock.
 */
public final class UnpacedGrantPolicy implements LeaseGrantPolicy {

    public static final UnpacedGrantPolicy INSTANCE = new UnpacedGrantPolicy();

    private UnpacedGrantPolicy() {}

    @Override
    public long capWantedMicros(long requestedMicros, long observedSpendMicros, long nowNanos) {
        if (requestedMicros < 0) {
            throw new IllegalArgumentException(
                    "requestedMicros must not be negative, was " + requestedMicros);
        }
        return requestedMicros;
    }
}
