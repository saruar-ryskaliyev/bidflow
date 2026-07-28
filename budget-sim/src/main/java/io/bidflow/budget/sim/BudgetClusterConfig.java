package io.bidflow.budget.sim;

import io.bidflow.budget.BudgetAuthority;
import io.bidflow.budget.LeaseGrantPolicy;

/**
 * Mutable fluent configuration for a {@link BudgetCluster} run.
 *
 * <p>Fields are public for test sweeps that tweak several knobs in one place; fluent setters
 * return {@code this} for chaining.
 */
public final class BudgetClusterConfig {

    private static final long MILLIS = 1_000_000L;

    int shardCount = 8;
    long budgetMicros = 10_000_000L;

    /** Face value requested per lease. Also the cap on what one lost lease can cost. */
    long leaseMicros = 200_000L;

    long leaseDurationNanos = 200 * MILLIS;
    long reclaimMarginNanos = BudgetAuthority.NEVER_RECLAIM;

    /** Renew once the wallet drops this low, or this long before the lease expires. */
    long lowWaterMicros = 40_000L;
    long renewAheadNanos = 20 * MILLIS;

    long requestCooldownNanos = 25 * MILLIS;
    long reportIntervalNanos = 50 * MILLIS;
    long sweepIntervalNanos = 25 * MILLIS;

    long minRequestIntervalNanos = 500_000L;
    long maxRequestIntervalNanos = 1_500_000L;

    /**
     * When true, early traffic is denser than late traffic — the shape pacing exists to
     * smooth out. The first half of the run draws from the short end of the interval
     * range; the second half draws from the long end.
     */
    boolean frontLoadedTraffic = false;

    /** Optional pacing; null keeps the historical unpaced grant behaviour. */
    LeaseGrantPolicy grantPolicy = null;

    /** How often to snapshot cumulative spend for pacing curve checks; 0 disables. */
    long checkpointIntervalNanos = 0L;

    /** The auction each request runs. Our campaign competes against seeded rivals. */
    int slots = 3;

    /** Kept positive so a won slot always carries a positive price. */
    long reserveMicros = 100L;

    int competitorCount = 6;
    long minCompetitorBidMicros = 100L;
    long maxCompetitorBidMicros = 1_000L;
    int minCompetitorQualityBps = 2_000;
    int maxCompetitorQualityBps = 10_000;
    long ourBidMicros = 800L;
    int ourQualityBps = 8_000;

    /** Default settings used by the safety and chaos sweeps. */
    public static BudgetClusterConfig defaults() {
        return new BudgetClusterConfig();
    }

    public BudgetClusterConfig shardCount(int value) {
        shardCount = value;
        return this;
    }

    /** Alias for {@link #shardCount(int)} used by older sweep helpers. */
    public BudgetClusterConfig withShardCount(int value) {
        return shardCount(value);
    }

    public BudgetClusterConfig budgetMicros(long value) {
        budgetMicros = value;
        return this;
    }

    public BudgetClusterConfig leaseMicros(long value) {
        leaseMicros = value;
        return this;
    }

    /** Alias for {@link #leaseMicros(long)} — each renewal asks for this face value. */
    public BudgetClusterConfig withTopUpMicros(long value) {
        return leaseMicros(value);
    }

    public BudgetClusterConfig leaseDurationNanos(long value) {
        leaseDurationNanos = value;
        return this;
    }

    public BudgetClusterConfig reclaimMarginNanos(long value) {
        reclaimMarginNanos = value;
        return this;
    }

    public BudgetClusterConfig lowWaterMicros(long value) {
        lowWaterMicros = value;
        return this;
    }

    public BudgetClusterConfig frontLoadedTraffic(boolean value) {
        frontLoadedTraffic = value;
        return this;
    }

    public BudgetClusterConfig grantPolicy(LeaseGrantPolicy value) {
        grantPolicy = value;
        return this;
    }

    public BudgetClusterConfig checkpointIntervalNanos(long value) {
        checkpointIntervalNanos = value;
        return this;
    }

    public BudgetClusterConfig minRequestIntervalNanos(long value) {
        minRequestIntervalNanos = value;
        return this;
    }

    public BudgetClusterConfig maxRequestIntervalNanos(long value) {
        maxRequestIntervalNanos = value;
        return this;
    }

    public int shardCount() {
        return shardCount;
    }

    public long budgetMicros() {
        return budgetMicros;
    }

    public long leaseMicros() {
        return leaseMicros;
    }

    public long leaseDurationNanos() {
        return leaseDurationNanos;
    }

    public long reclaimMarginNanos() {
        return reclaimMarginNanos;
    }

    public long lowWaterMicros() {
        return lowWaterMicros;
    }

    public long renewAheadNanos() {
        return renewAheadNanos;
    }

    public long requestCooldownNanos() {
        return requestCooldownNanos;
    }

    public long reportIntervalNanos() {
        return reportIntervalNanos;
    }

    public long sweepIntervalNanos() {
        return sweepIntervalNanos;
    }

    public long minRequestIntervalNanos() {
        return minRequestIntervalNanos;
    }

    public long maxRequestIntervalNanos() {
        return maxRequestIntervalNanos;
    }

    public boolean frontLoadedTraffic() {
        return frontLoadedTraffic;
    }

    public LeaseGrantPolicy grantPolicy() {
        return grantPolicy;
    }

    public long checkpointIntervalNanos() {
        return checkpointIntervalNanos;
    }

    public int slots() {
        return slots;
    }

    public long reserveMicros() {
        return reserveMicros;
    }

    public int competitorCount() {
        return competitorCount;
    }

    public long minCompetitorBidMicros() {
        return minCompetitorBidMicros;
    }

    public long maxCompetitorBidMicros() {
        return maxCompetitorBidMicros;
    }

    public int minCompetitorQualityBps() {
        return minCompetitorQualityBps;
    }

    public int maxCompetitorQualityBps() {
        return maxCompetitorQualityBps;
    }

    public long ourBidMicros() {
        return ourBidMicros;
    }

    public int ourQualityBps() {
        return ourQualityBps;
    }
}
