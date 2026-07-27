package io.bidflow.budget;

/**
 * One serving shard's wallet: the time-limited money it may spend without asking anyone.
 *
 * <p>{@link #tryReserve} sits on the request path and does two comparisons and an addition. No
 * locks, no allocation, no network, and no clock read — the caller supplies the current time,
 * because a serving thread already has a timestamp for its own logging and reading the clock
 * twice for one decision is waste at this scale.
 *
 * <h2>Why spending stops at the deadline</h2>
 *
 * <p>Refusing to spend past the lease expiry is what lets the authority reclaim money from a
 * shard it can no longer reach. The authority waits until the deadline has passed by a safety
 * margin and then takes the remainder back, and that is only sound if the holder really did
 * stop.
 *
 * <p>The catch is that the deadline is expressed on the authority's clock while this wallet can
 * only compare it against its own. If this shard's clock runs slow it keeps spending past the
 * moment the authority thinks the lease died. The overlap is bounded by the clock disagreement,
 * so the authority's safety margin has to cover it — that relationship is the whole subject of
 * the reclaim experiment, and it is why {@link io.bidflow.sim.NodeClock} exists.
 *
 * <h2>Sealing</h2>
 *
 * <p>Renewal is a two-part move: seal, then ask. {@link #sealForRenewal} stops spending and
 * returns a final figure, and only then does the shard tell the authority "lease 7 spent 340 of
 * its 500, please issue another". Sealing first is what makes that number trustworthy — report
 * a running total while still spending and the authority reclaims money that has since gone
 * out the door.
 *
 * <p>The cost is a serving gap of one round trip per renewal, during which this shard can spend
 * nothing. That is a real cost, but a small one: a lease lasting hundreds of milliseconds and a
 * round trip of hundreds of microseconds put the gap under a tenth of a percent.
 *
 * <p><b>Not thread-safe.</b> One instance per request-handling thread.
 */
public final class SpendAuthority {

    private final int shardId;
    private final long incarnation;

    private long leaseId = Lease.NONE;
    private long leaseAmountMicros;
    private long leaseSpentMicros;
    private long leaseExpiresAtNanos;

    /** Set when spending has stopped so that the reported figure is final. */
    private boolean sealed;

    private long lifetimeSpentMicros;

    /**
     * @param incarnation strictly increasing per restart of this shard, so grants aimed at a
     *     dead predecessor are recognisable. The wallet lives in memory and dies with its
     *     process, and a replacement that inherited its predecessor's authority would respend
     *     money already spent.
     */
    public SpendAuthority(int shardId, long incarnation) {
        if (shardId < 0) {
            throw new IllegalArgumentException("shardId must not be negative, was " + shardId);
        }
        if (incarnation <= 0) {
            throw new IllegalArgumentException("incarnation must be positive, was " + incarnation);
        }
        this.shardId = shardId;
        this.incarnation = incarnation;
    }

    /**
     * Commits spend against the current lease.
     *
     * <p>The hot path. Returns false rather than throwing for every ordinary refusal — out of
     * money, past the deadline, sealed for renewal — because none of those are errors. The ad
     * simply does not show.
     *
     * @param nowNanos this shard's own reading of the clock
     */
    public boolean tryReserve(long nowNanos, long amountMicros) {
        if (amountMicros < 0) {
            throw new IllegalArgumentException("amountMicros must not be negative, was " + amountMicros);
        }
        if (sealed || leaseId == Lease.NONE || nowNanos >= leaseExpiresAtNanos) {
            return false;
        }
        // Compared on the remaining side rather than by adding to the spent side, so a huge
        // amount cannot overflow its way past the check.
        if (leaseAmountMicros - leaseSpentMicros < amountMicros) {
            return false;
        }
        leaseSpentMicros += amountMicros;
        lifetimeSpentMicros += amountMicros;
        return true;
    }

    /**
     * Adopts a newly granted lease.
     *
     * <p>Refuses a lease not newer than the one held, which makes a duplicated or reordered
     * grant a no-op. Also refuses to displace a lease that is still live and unsealed: doing so
     * would discard that lease's spend record while the authority still expects to be told it,
     * and the authority would then reclaim money that had already been spent.
     *
     * @return true if the lease was adopted
     */
    public boolean installLease(Lease lease, long nowNanos) {
        if (lease.leaseId() <= leaseId) {
            return false;
        }
        final boolean replaceable =
                leaseId == Lease.NONE || sealed || nowNanos >= leaseExpiresAtNanos;
        if (!replaceable) {
            return false;
        }
        leaseId = lease.leaseId();
        leaseAmountMicros = lease.amountMicros();
        leaseExpiresAtNanos = lease.expiresAtNanos();
        leaseSpentMicros = 0L;
        sealed = false;
        return true;
    }

    /**
     * Stops spending on the current lease and returns what it spent, ready to report.
     *
     * <p>Idempotent: once sealed the figure cannot move, so a retried renewal request carries
     * the same number.
     */
    public long sealForRenewal() {
        sealed = true;
        return leaseSpentMicros;
    }

    /** True when it is time to seal and ask for another lease. */
    public boolean needsLease(long nowNanos, long lowWaterMicros, long renewAheadNanos) {
        if (sealed || leaseId == Lease.NONE) {
            return true;
        }
        if (nowNanos >= leaseExpiresAtNanos - renewAheadNanos) {
            return true;
        }
        return remainingMicros() <= lowWaterMicros;
    }

    public boolean isExpired(long nowNanos) {
        return leaseId == Lease.NONE || nowNanos >= leaseExpiresAtNanos;
    }

    public boolean isSealed() {
        return sealed;
    }

    public int shardId() {
        return shardId;
    }

    public long incarnation() {
        return incarnation;
    }

    public long leaseId() {
        return leaseId;
    }

    /** Unspent authority on the current lease, ignoring whether it is still valid. */
    public long remainingMicros() {
        return leaseAmountMicros - leaseSpentMicros;
    }

    public long leaseAmountMicros() {
        return leaseAmountMicros;
    }

    /** Spend against the current lease, which is what gets reported. */
    public long leaseSpentMicros() {
        return leaseSpentMicros;
    }

    public long leaseExpiresAtNanos() {
        return leaseExpiresAtNanos;
    }

    /** Spend across every lease this incarnation has held. */
    public long lifetimeSpentMicros() {
        return lifetimeSpentMicros;
    }
}
