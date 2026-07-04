package io.bidflow.budget;

/**
 * The bank: sole owner of a campaign's budget, handing out spend authority to serving shards
 * in advance so no shard has to ask permission per request.
 *
 * <h2>The one invariant</h2>
 *
 * <p>Everything rests on a single rule enforced in {@link #requestAuthority}:
 *
 * <pre>{@code sum over shards of grantedMicros <= budgetMicros}</pre>
 *
 * <p>A shard can only spend what it has been granted, and grants never total more than the
 * budget, so total spend cannot exceed the budget. Notice what that argument does not mention:
 * clocks, message ordering, retries, duplicates, or crashes. That is exactly why it is worth
 * having — it holds under every fault the simulator can inject, because none of those things
 * appear in it.
 *
 * <p>The price of an argument that strong is paid in efficiency. Authority granted to a shard
 * that then crashes or goes idle is money nobody can spend, and this class never takes it
 * back, so it stays stranded and the campaign underdelivers. Reclaiming it is the obvious next
 * step and the dangerous one: a revocation racing a shard that is still spending is precisely
 * how overspend gets introduced, and the size of that race depends on clock skew. Building the
 * unconditionally safe version first means the cost of revocation can later be measured
 * against a known-good baseline rather than guessed at.
 *
 * <h2>Incarnations</h2>
 *
 * <p>A shard's wallet lives in memory and dies with its process. The replacement cannot know
 * what its predecessor spent, so handing it the shard's lifetime authority total would let it
 * respend everything already spent.
 *
 * <p>So authority is scoped to an incarnation. A request carrying a higher incarnation than
 * the authority has seen is treated as an implicit announcement that the shard restarted: the
 * authority already granted is written off, and the new incarnation begins from zero. Requests
 * from an incarnation already superseded are refused outright. This keeps the invariant intact
 * across crashes at the cost of stranding whatever the dead process had not yet spent.
 *
 * <h2>Why reports are cumulative</h2>
 *
 * <p>Shards report their incarnation's spend so far, not deltas, and {@link #recordReport}
 * keeps the highest figure per shard. That makes reporting immune to the network with no
 * deduplication table: a duplicate is a no-op and a stale report cannot walk spend backwards.
 * An incremental report would need exactly-once delivery to stay correct, and networks do not
 * offer that.
 *
 * <p>Reports do not affect safety at all — they are how the authority learns what happened,
 * for pacing and for advertiser reporting. Safety comes from the grant side alone.
 *
 * <p><b>Not thread-safe.</b> This is a single logical owner; a real deployment would replicate
 * it for availability, and the durability of the granted totals is what would stop a crash
 * from re-granting money already spent.
 */
public final class BudgetAuthority {

    /** Returned by {@link #requestAuthority} when the caller has already been superseded. */
    public static final long SUPERSEDED = -1L;

    private final long budgetMicros;

    /** Lifetime micros granted per shard, across all its incarnations. */
    private final long[] grantedMicros;

    /** Value of {@link #grantedMicros} when the current incarnation began. */
    private final long[] incarnationBaseMicros;

    private final long[] incarnations;

    /** Highest lifetime spend each shard has reported. */
    private final long[] reportedSpentMicros;

    /** Value of {@link #reportedSpentMicros} when the current incarnation began. */
    private final long[] reportedBaseMicros;

    private long totalGrantedMicros;
    private long grantsIssued;
    private long grantsExhausted;
    private long grantsSuperseded;
    private long restartsObserved;

    public BudgetAuthority(long budgetMicros, int shardCount) {
        if (budgetMicros < 0) {
            throw new IllegalArgumentException("budgetMicros must not be negative, was " + budgetMicros);
        }
        if (shardCount <= 0) {
            throw new IllegalArgumentException("shardCount must be positive, was " + shardCount);
        }
        this.budgetMicros = budgetMicros;
        this.grantedMicros = new long[shardCount];
        this.incarnationBaseMicros = new long[shardCount];
        this.incarnations = new long[shardCount];
        this.reportedSpentMicros = new long[shardCount];
        this.reportedBaseMicros = new long[shardCount];
    }

    /**
     * Grants a shard incarnation as much of {@code wantedMicros} as the remaining budget
     * allows.
     *
     * @param incarnation the caller's incarnation; a higher value than last seen is taken as
     *     notice that the shard restarted
     * @return the incarnation's new total authority, or {@link #SUPERSEDED} if the caller has
     *     been replaced by a later incarnation. The figure is cumulative rather than an
     *     increment, which is what lets a duplicated reply be applied harmlessly.
     */
    public long requestAuthority(int shardId, long incarnation, long wantedMicros) {
        checkShard(shardId);
        if (incarnation < 0) {
            throw new IllegalArgumentException("incarnation must not be negative, was " + incarnation);
        }
        if (wantedMicros < 0) {
            throw new IllegalArgumentException("wantedMicros must not be negative, was " + wantedMicros);
        }
        if (incarnation < incarnations[shardId]) {
            grantsSuperseded++;
            return SUPERSEDED;
        }
        if (incarnation > incarnations[shardId]) {
            // The shard restarted. Whatever its predecessor held is written off: we cannot
            // know how much of it was spent, so we must assume all of it was.
            //
            // A shard's first ever request also advances the incarnation, from the initial
            // zero, but that is an introduction rather than a restart and must not be counted
            // as one.
            if (incarnations[shardId] > 0) {
                restartsObserved++;
            }
            incarnations[shardId] = incarnation;
            incarnationBaseMicros[shardId] = grantedMicros[shardId];
            reportedBaseMicros[shardId] = reportedSpentMicros[shardId];
        }
        final long headroom = budgetMicros - totalGrantedMicros;
        final long granted = Math.min(wantedMicros, headroom);
        if (granted > 0) {
            grantedMicros[shardId] += granted;
            totalGrantedMicros += granted;
            grantsIssued++;
        } else {
            grantsExhausted++;
        }
        return grantedMicros[shardId] - incarnationBaseMicros[shardId];
    }

    /**
     * Records a shard's report of what its current incarnation has spent.
     *
     * @param incarnationSpentMicros spend by that incarnation, not lifetime spend by the shard
     */
    public void recordReport(int shardId, long incarnation, long incarnationSpentMicros) {
        checkShard(shardId);
        if (incarnationSpentMicros < 0) {
            throw new IllegalArgumentException(
                    "incarnationSpentMicros must not be negative, was " + incarnationSpentMicros);
        }
        if (incarnation != incarnations[shardId]) {
            return;
        }
        final long lifetime = reportedBaseMicros[shardId] + incarnationSpentMicros;
        if (lifetime > reportedSpentMicros[shardId]) {
            reportedSpentMicros[shardId] = lifetime;
        }
    }

    public long budgetMicros() {
        return budgetMicros;
    }

    public int shardCount() {
        return grantedMicros.length;
    }

    /** Total authority handed out. The invariant is that this never exceeds the budget. */
    public long totalGrantedMicros() {
        return totalGrantedMicros;
    }

    /** Budget not yet promised to anyone. */
    public long unallocatedMicros() {
        return budgetMicros - totalGrantedMicros;
    }

    public long grantedMicros(int shardId) {
        checkShard(shardId);
        return grantedMicros[shardId];
    }

    public long incarnation(int shardId) {
        checkShard(shardId);
        return incarnations[shardId];
    }

    public long reportedSpentMicros(int shardId) {
        checkShard(shardId);
        return reportedSpentMicros[shardId];
    }

    public long totalReportedSpentMicros() {
        long total = 0;
        for (long reported : reportedSpentMicros) {
            total += reported;
        }
        return total;
    }

    /**
     * Authority granted to incarnations that no longer exist, minus what they managed to
     * report spending — an estimate of budget permanently lost to restarts.
     *
     * <p>An estimate rather than a fact, because a process that died mid-request may have spent
     * money it never reported. That uncertainty is the whole reason the authority cannot safely
     * reclaim this money.
     */
    public long strandedByRestartsMicros() {
        long stranded = 0;
        for (int shard = 0; shard < grantedMicros.length; shard++) {
            stranded += incarnationBaseMicros[shard] - reportedBaseMicros[shard];
        }
        return stranded;
    }

    public long grantsIssued() {
        return grantsIssued;
    }

    /** Requests that arrived with the budget already fully allocated. */
    public long grantsExhausted() {
        return grantsExhausted;
    }

    /** Requests refused because the caller had already been replaced. */
    public long grantsSuperseded() {
        return grantsSuperseded;
    }

    public long restartsObserved() {
        return restartsObserved;
    }

    private void checkShard(int shardId) {
        if (shardId < 0 || shardId >= grantedMicros.length) {
            throw new IllegalArgumentException(
                    "shardId must be in [0, " + grantedMicros.length + "), was " + shardId);
        }
    }
}
