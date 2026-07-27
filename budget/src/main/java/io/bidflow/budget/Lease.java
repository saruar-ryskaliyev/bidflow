package io.bidflow.budget;

/**
 * A time-limited grant of spend authority to one shard.
 *
 * <p>The expiry is what makes reclaiming possible. Without it the authority can never take
 * money back from a shard that has crashed or been partitioned away, because it has no basis
 * for concluding the shard will never spend it. With it, the authority can wait out the
 * deadline and recover the remainder — at the cost of having to reason about whether the
 * holder agrees about what time it is.
 *
 * @param leaseId strictly increasing per shard, so a wallet can reject a lease older than the
 *     one it already holds and a duplicated grant is a no-op
 * @param amountMicros how much this lease authorises
 * @param expiresAtNanos the instant, <em>on the authority's clock</em>, after which the holder
 *     must stop spending. The holder can only compare it against its own clock, and the gap
 *     between those two readings is the entire safety problem.
 */
public record Lease(long leaseId, long amountMicros, long expiresAtNanos) {

    /** A lease id lower than any real one, meaning "no lease". */
    public static final long NONE = 0L;

    public Lease {
        if (leaseId <= NONE) {
            throw new IllegalArgumentException("leaseId must be positive, was " + leaseId);
        }
        if (amountMicros < 0) {
            throw new IllegalArgumentException("amountMicros must not be negative, was " + amountMicros);
        }
    }
}
