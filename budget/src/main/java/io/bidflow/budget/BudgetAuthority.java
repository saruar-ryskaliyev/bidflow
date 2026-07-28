package io.bidflow.budget;

import java.util.ArrayList;
import java.util.List;

/**
 * The bank: sole owner of a campaign's budget, leasing spend authority to serving shards in
 * advance so no shard needs a network round trip per request.
 *
 * <h2>The accounting</h2>
 *
 * <p>Two numbers bound everything:
 *
 * <pre>{@code settledMicros + outstandingMicros <= budgetMicros}</pre>
 *
 * <p>{@code settled} is spend accepted as final. {@code outstanding} is the full face value of
 * every live lease, counted as though it will be spent in its entirety. A shard can never spend
 * more than its lease, so actual spend can never exceed the sum, and the sum can never exceed
 * the budget. New leases are issued only from what is left over.
 *
 * <h2>Two ways money comes back, and only one of them is free</h2>
 *
 * <p><b>Voluntary release.</b> A shard seals a lease, meaning it has stopped spending, and
 * reports the final figure when it asks for the next one. The authority settles that figure and
 * returns the difference to the pool. This is exactly safe: the holder knew its own final number
 * and had stopped. No clock appears in the argument.
 *
 * <p><b>Unilateral reclaim.</b> A shard that crashed or was partitioned away never releases
 * anything, and that is where most stranded budget accumulates. Recovering it means waiting for
 * the lease to expire and taking the remainder back. This is <em>not</em> free, and it fails in
 * two distinct ways worth separating.
 *
 * <p>First, the holder may still be spending. It stops when its own clock passes the expiry,
 * while the authority reclaims when its clock passes expiry plus {@code reclaimMargin}. If
 * clocks disagree by at most {@code maxSkew}, a margin of at least {@code maxSkew} closes the
 * overlap completely; a smaller margin leaves a window of {@code maxSkew - margin} in which both
 * parties believe they own the money.
 *
 * <p>Second — and this one no margin can fix — the authority only knows what the shard
 * <em>reported</em>. A partitioned shard's reports never arrived, so its last known figure may
 * be far below what it actually spent, and settling the low figure frees money that is already
 * gone. The exposure per lost lease is therefore the unreported portion, which is at worst the
 * whole lease. That makes <b>lease size</b> the control for this risk rather than the margin:
 * smaller leases cap the damage, at the price of more coordination traffic.
 *
 * <p>Setting {@link #NEVER_RECLAIM} as the margin disables unilateral reclaim entirely, which
 * recovers the unconditionally safe behaviour and makes it one end of the trade-off curve rather
 * than a separate implementation.
 *
 * <h2>Incarnations</h2>
 *
 * <p>A wallet lives in memory and dies with its process, so a replacement cannot know what its
 * predecessor spent. A request carrying a higher incarnation is taken as notice of a restart;
 * requests from a superseded incarnation are refused. The dead process's lease is deliberately
 * left outstanding so the expiry sweeper handles it — a crashed shard has certainly stopped
 * spending, which makes it the safest possible case for reclaim.
 *
 * <p><b>Not thread-safe.</b> A single logical owner; a real deployment would replicate it, and
 * the durability of its accounting is what would stop a crash from re-leasing spent money.
 */
public final class BudgetAuthority {

    /** A reclaim margin that never elapses, disabling unilateral reclaim. */
    public static final long NEVER_RECLAIM = Long.MAX_VALUE;

    private final long budgetMicros;
    private final long leaseDurationNanos;
    private final long reclaimMarginNanos;
    private final LeaseGrantPolicy grantPolicy;

    private final long[] incarnations;
    private final long[] nextLeaseIds;

    private final List<Outstanding> outstanding = new ArrayList<>();

    private long settledMicros;
    private long outstandingMicros;

    private long leasesIssued;
    private long leasesRetransmitted;
    private long leasesExhausted;
    private long leasesSuperseded;
    private long leasesReleased;
    private long leasesReclaimed;
    private long releasedMicros;
    private long reclaimedMicros;
    private long restartsObserved;
    private long leasesPaced;

    /**
     * Unpaced authority — grants whatever headroom allows. Prefer the overload that takes a
     * {@link LeaseGrantPolicy} when spend should follow a target curve.
     *
     * @param leaseDurationNanos how long each lease stays valid. Also the cap on what a single
     *     unreachable shard can cost, since exposure per lost lease is bounded by its size.
     * @param reclaimMarginNanos how long after expiry the authority waits before taking a lease
     *     back, or {@link #NEVER_RECLAIM} to never do so. Must be at least the worst-case clock
     *     disagreement for the holder to be guaranteed to have stopped.
     */
    public BudgetAuthority(
            long budgetMicros, int shardCount, long leaseDurationNanos, long reclaimMarginNanos) {
        this(budgetMicros, shardCount, leaseDurationNanos, reclaimMarginNanos, UnpacedGrantPolicy.INSTANCE);
    }

    /**
     * @param grantPolicy caps {@code wantedMicros} before a lease is minted; use
     *     {@link UnpacedGrantPolicy#INSTANCE} for the historical behaviour
     */
    public BudgetAuthority(
            long budgetMicros,
            int shardCount,
            long leaseDurationNanos,
            long reclaimMarginNanos,
            LeaseGrantPolicy grantPolicy) {
        if (budgetMicros < 0) {
            throw new IllegalArgumentException("budgetMicros must not be negative, was " + budgetMicros);
        }
        if (shardCount <= 0) {
            throw new IllegalArgumentException("shardCount must be positive, was " + shardCount);
        }
        if (leaseDurationNanos <= 0) {
            throw new IllegalArgumentException(
                    "leaseDurationNanos must be positive, was " + leaseDurationNanos);
        }
        if (reclaimMarginNanos < 0) {
            throw new IllegalArgumentException(
                    "reclaimMarginNanos must not be negative, was " + reclaimMarginNanos);
        }
        if (grantPolicy == null) {
            throw new IllegalArgumentException("grantPolicy must not be null");
        }
        this.budgetMicros = budgetMicros;
        this.leaseDurationNanos = leaseDurationNanos;
        this.reclaimMarginNanos = reclaimMarginNanos;
        this.grantPolicy = grantPolicy;
        this.incarnations = new long[shardCount];
        this.nextLeaseIds = new long[shardCount];
    }

    /**
     * Settles a sealed lease if one is named, then issues a fresh lease from whatever budget is
     * left.
     *
     * <p>A retry that arrives while a grant <em>newer than anything the caller holds</em> is
     * still live is answered by retransmitting that grant rather than minting another, so a
     * slow or lost reply does not strand additional leases. Held and settled are separate
     * parameters because under prefetch they differ: the shard settles its previous lease
     * while holding — and still spending — the current one, and conflating the two would make
     * the authority retransmit a grant the shard already installed, starving it of the next.
     *
     * @param heldLeaseId the newest lease the caller has installed, or {@link Lease#NONE}
     * @param sealedLeaseId the lease the caller has stopped spending on, or {@link Lease#NONE}
     * @param sealedSpentMicros that lease's final spend, meaningful only if it was sealed first
     * @return the new lease, or null if the caller is superseded or the budget is exhausted
     */
    public Lease requestLease(
            int shardId,
            long incarnation,
            long heldLeaseId,
            long sealedLeaseId,
            long sealedSpentMicros,
            long wantedMicros,
            long nowNanos) {
        checkShard(shardId);
        if (incarnation <= 0) {
            throw new IllegalArgumentException("incarnation must be positive, was " + incarnation);
        }
        if (wantedMicros < 0) {
            throw new IllegalArgumentException("wantedMicros must not be negative, was " + wantedMicros);
        }
        if (incarnation < incarnations[shardId]) {
            leasesSuperseded++;
            return null;
        }
        if (incarnation > incarnations[shardId]) {
            if (incarnations[shardId] > 0) {
                restartsObserved++;
            }
            incarnations[shardId] = incarnation;
            // The predecessor's lease stays outstanding on purpose. It will be swept at expiry,
            // and a dead process is the one case where reclaim is certain not to race a spender.
        }
        if (sealedLeaseId != Lease.NONE) {
            release(shardId, incarnation, sealedLeaseId, sealedSpentMicros);
        }

        // A shard that asks again because the previous grant's reply is slow or lost gets
        // that grant retransmitted, not a second lease minted. Every extra mint strands
        // face value until the sweeper collects it, which is the request stampede the
        // efficiency measurements flagged. Retransmission is safe because the wallet
        // already treats a duplicated or superseded grant as a no-op.
        final Outstanding pending = newestOutstanding(shardId, incarnation);
        if (pending != null && pending.leaseId > heldLeaseId && nowNanos < pending.expiresAtNanos) {
            leasesRetransmitted++;
            return new Lease(pending.leaseId, pending.amountMicros, pending.expiresAtNanos);
        }

        final long pacedWanted = grantPolicy.capWantedMicros(wantedMicros, observedSpendMicros(), nowNanos);
        if (pacedWanted < wantedMicros) {
            leasesPaced++;
        }
        final long headroom = budgetMicros - settledMicros - outstandingMicros;
        final long amount = Math.min(pacedWanted, headroom);
        if (amount <= 0) {
            leasesExhausted++;
            return null;
        }
        final long leaseId = ++nextLeaseIds[shardId];
        final Lease lease = new Lease(leaseId, amount, nowNanos + leaseDurationNanos);
        outstanding.add(new Outstanding(shardId, incarnation, leaseId, amount, lease.expiresAtNanos()));
        outstandingMicros += amount;
        leasesIssued++;
        return lease;
    }

    /**
     * Settles a finished lease at its final figure without granting anything in return.
     *
     * <p>This is the prompt half of the prefetch protocol: the moment a shard displaces a
     * live lease with a newer grant, it sends the displaced lease's final figure here, so
     * the unspent remainder returns to the pool without waiting for the next renewal.
     * Idempotent — a duplicated release, or one racing a renewal that names the same
     * lease, finds nothing left to settle.
     */
    public void releaseSealed(int shardId, long incarnation, long leaseId, long finalSpentMicros) {
        checkShard(shardId);
        if (finalSpentMicros < 0) {
            throw new IllegalArgumentException(
                    "finalSpentMicros must not be negative, was " + finalSpentMicros);
        }
        release(shardId, incarnation, leaseId, finalSpentMicros);
    }

    /**
     * Records how much of a live lease has been spent so far.
     *
     * <p>Keeps the highest figure seen, so duplicated and reordered reports are both harmless
     * without any deduplication table. These reports do not affect safety while the lease is
     * live — the lease's full face value is already reserved — but they are what limits the
     * damage if the lease later has to be reclaimed without its holder's cooperation.
     */
    public void recordReport(int shardId, long incarnation, long leaseId, long spentSoFarMicros) {
        checkShard(shardId);
        if (spentSoFarMicros < 0) {
            throw new IllegalArgumentException(
                    "spentSoFarMicros must not be negative, was " + spentSoFarMicros);
        }
        final Outstanding lease = find(shardId, incarnation, leaseId);
        if (lease == null) {
            return;
        }
        final long capped = Math.min(spentSoFarMicros, lease.amountMicros);
        if (capped > lease.reportedSpentMicros) {
            lease.reportedSpentMicros = capped;
        }
    }

    /**
     * Takes back every lease whose expiry has passed by the reclaim margin, settling each at its
     * last reported spend.
     *
     * <p>Intended to be called on a timer. Anything freed here is money the authority is betting
     * was never spent, and the bet is only as good as the reports it has.
     *
     * @return how many leases were reclaimed
     */
    public int reclaimExpired(long nowNanos) {
        if (reclaimMarginNanos == NEVER_RECLAIM) {
            return 0;
        }
        int reclaimed = 0;
        int keep = 0;
        for (int i = 0; i < outstanding.size(); i++) {
            final Outstanding lease = outstanding.get(i);
            // Written as a subtraction so that a distant expiry plus a large margin cannot
            // overflow into the past.
            final boolean due = nowNanos - lease.expiresAtNanos >= reclaimMarginNanos;
            if (due) {
                settledMicros += lease.reportedSpentMicros;
                outstandingMicros -= lease.amountMicros;
                reclaimedMicros += lease.amountMicros - lease.reportedSpentMicros;
                leasesReclaimed++;
                reclaimed++;
            } else {
                outstanding.set(keep++, lease);
            }
        }
        while (outstanding.size() > keep) {
            outstanding.remove(outstanding.size() - 1);
        }
        return reclaimed;
    }

    private void release(int shardId, long incarnation, long leaseId, long spentMicros) {
        final int index = indexOf(shardId, incarnation, leaseId);
        if (index < 0) {
            // Already reclaimed by the sweeper, or a duplicated request. Either way there is
            // nothing left to settle and re-settling would double-count.
            return;
        }
        final Outstanding lease = outstanding.get(index);
        final long finalSpend = Math.min(Math.max(spentMicros, lease.reportedSpentMicros), lease.amountMicros);
        settledMicros += finalSpend;
        outstandingMicros -= lease.amountMicros;
        releasedMicros += lease.amountMicros - finalSpend;
        leasesReleased++;
        outstanding.remove(index);
    }

    private Outstanding find(int shardId, long incarnation, long leaseId) {
        final int index = indexOf(shardId, incarnation, leaseId);
        return index < 0 ? null : outstanding.get(index);
    }

    /** The live lease this incarnation was granted most recently, or null. */
    private Outstanding newestOutstanding(int shardId, long incarnation) {
        Outstanding newest = null;
        for (int i = 0; i < outstanding.size(); i++) {
            final Outstanding lease = outstanding.get(i);
            if (lease.shardId == shardId
                    && lease.incarnation == incarnation
                    && (newest == null || lease.leaseId > newest.leaseId)) {
                newest = lease;
            }
        }
        return newest;
    }

    private int indexOf(int shardId, long incarnation, long leaseId) {
        for (int i = 0; i < outstanding.size(); i++) {
            final Outstanding lease = outstanding.get(i);
            if (lease.shardId == shardId && lease.incarnation == incarnation && lease.leaseId == leaseId) {
                return i;
            }
        }
        return -1;
    }

    public long budgetMicros() {
        return budgetMicros;
    }

    public int shardCount() {
        return incarnations.length;
    }

    public long leaseDurationNanos() {
        return leaseDurationNanos;
    }

    public long reclaimMarginNanos() {
        return reclaimMarginNanos;
    }

    /** Spend accepted as final. */
    public long settledMicros() {
        return settledMicros;
    }

    /** Face value of all live leases, counted as if it will all be spent. */
    public long outstandingMicros() {
        return outstandingMicros;
    }

    /** Budget available to lease out. */
    public long headroomMicros() {
        return budgetMicros - settledMicros - outstandingMicros;
    }

    /**
     * Best current estimate of spend: settled totals plus the latest reported figure on every
     * live lease. Pacing uses this rather than settled alone so in-flight spend is visible.
     */
    public long observedSpendMicros() {
        long observed = settledMicros;
        for (int i = 0; i < outstanding.size(); i++) {
            observed += outstanding.get(i).reportedSpentMicros;
        }
        return observed;
    }

    public int outstandingLeaseCount() {
        return outstanding.size();
    }

    /** Returned to the pool by shards that sealed a lease and handed back the remainder. */
    public long releasedMicros() {
        return releasedMicros;
    }

    /** Taken back on expiry without the holder's cooperation. The risky recovery. */
    public long reclaimedMicros() {
        return reclaimedMicros;
    }

    public long leasesIssued() {
        return leasesIssued;
    }

    /** Requests answered by re-sending the still-live previous grant instead of minting. */
    public long leasesRetransmitted() {
        return leasesRetransmitted;
    }

    /** Requests that found the budget fully committed. */
    public long leasesExhausted() {
        return leasesExhausted;
    }

    /** Requests whose face value was reduced by the grant policy before minting. */
    public long leasesPaced() {
        return leasesPaced;
    }

    public long leasesSuperseded() {
        return leasesSuperseded;
    }

    public long leasesReleased() {
        return leasesReleased;
    }

    public long leasesReclaimed() {
        return leasesReclaimed;
    }

    public long restartsObserved() {
        return restartsObserved;
    }

    private void checkShard(int shardId) {
        if (shardId < 0 || shardId >= incarnations.length) {
            throw new IllegalArgumentException(
                    "shardId must be in [0, " + incarnations.length + "), was " + shardId);
        }
    }

    /** A live lease the authority is still waiting to hear about. */
    private static final class Outstanding {
        private final int shardId;
        private final long incarnation;
        private final long leaseId;
        private final long amountMicros;
        private final long expiresAtNanos;
        private long reportedSpentMicros;

        private Outstanding(
                int shardId, long incarnation, long leaseId, long amountMicros, long expiresAtNanos) {
            this.shardId = shardId;
            this.incarnation = incarnation;
            this.leaseId = leaseId;
            this.amountMicros = amountMicros;
            this.expiresAtNanos = expiresAtNanos;
        }
    }
}
