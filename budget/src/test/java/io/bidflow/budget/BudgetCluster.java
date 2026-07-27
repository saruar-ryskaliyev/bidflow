package io.bidflow.budget;

import io.bidflow.sim.NetworkConditions;
import io.bidflow.sim.NodeClock;
import io.bidflow.sim.SimNetwork;
import io.bidflow.sim.Simulation;

/**
 * A whole budget-enforcement deployment inside the simulator: one authority, many serving
 * shards, traffic trying to spend money, and faults free to interrupt any of it.
 *
 * <p>The authority is node 0 and shard {@code s} is node {@code s + 1}, so the simulator's crash
 * and partition controls address them directly.
 *
 * <p>The authority's clock is treated as the reference frame with zero error, and shards are
 * skewed relative to it. Skew is relative, so this loses no generality and gives the reclaim
 * margin a single unambiguous quantity to be compared against.
 *
 * <p>{@link #actualSpendMicros()} is the number that matters. The harness increments it itself on
 * every committed spend, never deriving it from anything the authority or the shards believe. If
 * the measurement came out of the components under test, a bug that corrupted the accounting
 * would also corrupt the measurement, and the test would pass while the money went missing.
 */
final class BudgetCluster {

    static final int AUTHORITY_NODE = 0;
    private static final long MILLIS = 1_000_000L;

    /** Mutable and fluent, because the sweep varies several of these per run. */
    static final class Config {
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

        long minCostMicros = 100L;
        long maxCostMicros = 900L;
        long minRequestIntervalNanos = 500_000L;
        long maxRequestIntervalNanos = 1_500_000L;

        Config shardCount(int value) {
            shardCount = value;
            return this;
        }

        Config budgetMicros(long value) {
            budgetMicros = value;
            return this;
        }

        Config leaseMicros(long value) {
            leaseMicros = value;
            return this;
        }

        Config leaseDurationNanos(long value) {
            leaseDurationNanos = value;
            return this;
        }

        Config reclaimMarginNanos(long value) {
            reclaimMarginNanos = value;
            return this;
        }

        Config lowWaterMicros(long value) {
            lowWaterMicros = value;
            return this;
        }
    }

    private final Simulation sim;
    private final SimNetwork net;
    private final Config config;
    private final BudgetAuthority authority;
    private final NodeClock authorityClock;
    private final SpendAuthority[] wallets;
    private final NodeClock[] clocks;
    private final long[] incarnations;
    private final long[] nextRequestAllowedAt;
    private final long[] nextReportAt;

    private long actualSpendMicros;
    private long servedRequests;
    private long refusedRequests;
    private long restarts;

    BudgetCluster(Simulation sim, Config config, NetworkConditions conditions) {
        this(sim, config, conditions, new long[config.shardCount]);
    }

    BudgetCluster(Simulation sim, Config config, NetworkConditions conditions, long[] clockOffsetsNanos) {
        if (clockOffsetsNanos.length != config.shardCount) {
            throw new IllegalArgumentException(
                    "expected " + config.shardCount + " clock offsets, got " + clockOffsetsNanos.length);
        }
        this.sim = sim;
        this.config = config;
        this.net = new SimNetwork(sim, config.shardCount + 1, conditions);
        this.authority = new BudgetAuthority(
                config.budgetMicros, config.shardCount, config.leaseDurationNanos, config.reclaimMarginNanos);
        this.authorityClock = new NodeClock(sim, 0L);
        this.wallets = new SpendAuthority[config.shardCount];
        this.clocks = new NodeClock[config.shardCount];
        this.incarnations = new long[config.shardCount];
        this.nextRequestAllowedAt = new long[config.shardCount];
        this.nextReportAt = new long[config.shardCount];

        for (int shard = 0; shard < config.shardCount; shard++) {
            incarnations[shard] = 1L;
            wallets[shard] = new SpendAuthority(shard, 1L);
            clocks[shard] = new NodeClock(sim, clockOffsetsNanos[shard]);
        }
    }

    void start() {
        for (int shard = 0; shard < config.shardCount; shard++) {
            scheduleTraffic(shard);
        }
        scheduleSweep();
    }

    /**
     * Kills a shard's process and starts a replacement.
     *
     * <p>The wallet is discarded rather than carried over, because it lived in memory. The
     * replacement therefore has no authority and no record of what its predecessor spent.
     */
    void restartShard(int shard) {
        sim.crash(nodeOf(shard));
        incarnations[shard]++;
        wallets[shard] = new SpendAuthority(shard, incarnations[shard]);
        nextRequestAllowedAt[shard] = 0L;
        nextReportAt[shard] = 0L;
        restarts++;
        scheduleTraffic(shard);
    }

    /** The authority's expiry sweeper, the only place unilateral reclaim happens. */
    private void scheduleSweep() {
        sim.schedule(config.sweepIntervalNanos, AUTHORITY_NODE, () -> {
            authority.reclaimExpired(authorityClock.nanos());
            scheduleSweep();
        });
    }

    private void scheduleTraffic(int shard) {
        final long span = config.maxRequestIntervalNanos - config.minRequestIntervalNanos;
        final long delay = config.minRequestIntervalNanos + (span == 0 ? 0 : sim.random().nextLong(span + 1));
        sim.schedule(delay, nodeOf(shard), () -> {
            final long now = clocks[shard].nanos();
            serveOne(shard, now);
            maybeRenew(shard, now);
            maybeReport(shard, now);
            scheduleTraffic(shard);
        });
    }

    private void serveOne(int shard, long now) {
        final long span = config.maxCostMicros - config.minCostMicros;
        final long cost = config.minCostMicros + (span == 0 ? 0 : sim.random().nextLong(span + 1));
        if (wallets[shard].tryReserve(now, cost)) {
            actualSpendMicros += cost;
            servedRequests++;
        } else {
            refusedRequests++;
        }
    }

    /**
     * Seals the current lease and asks for another.
     *
     * <p>Sealing before asking is what makes the reported figure final, and it is why the shard
     * spends nothing for one round trip. Rate-limited on the shard's own clock rather than
     * guarded by an in-flight flag: a flag would strand the shard permanently if the reply were
     * lost, whereas a cooldown retries on the next traffic tick.
     */
    private void maybeRenew(int shard, long now) {
        final SpendAuthority wallet = wallets[shard];
        if (!wallet.needsLease(now, config.lowWaterMicros, config.renewAheadNanos)) {
            return;
        }
        if (now < nextRequestAllowedAt[shard]) {
            return;
        }
        nextRequestAllowedAt[shard] = now + config.requestCooldownNanos;

        final long incarnation = wallet.incarnation();
        final long sealedLeaseId = wallet.leaseId();
        final long sealedSpent = wallet.sealForRenewal();

        net.send(nodeOf(shard), AUTHORITY_NODE, "lease-req s" + shard + " i" + incarnation, () -> {
            final Lease lease = authority.requestLease(
                    shard, incarnation, sealedLeaseId, sealedSpent, config.leaseMicros, authorityClock.nanos());
            if (lease == null) {
                return;
            }
            net.send(AUTHORITY_NODE, nodeOf(shard), "lease s" + shard + " #" + lease.leaseId(), () -> {
                final SpendAuthority current = wallets[shard];
                if (current.incarnation() != incarnation) {
                    return;
                }
                current.installLease(lease, clocks[shard].nanos());
            });
        });
    }

    /**
     * Tells the authority how much of the live lease has gone.
     *
     * <p>Irrelevant while the shard is healthy, since the lease's full value is already reserved.
     * It matters only if this shard later becomes unreachable and its lease has to be reclaimed
     * without its cooperation — the last report received is then all that stands between the
     * authority and re-leasing money that was already spent.
     */
    private void maybeReport(int shard, long now) {
        if (now < nextReportAt[shard]) {
            return;
        }
        nextReportAt[shard] = now + config.reportIntervalNanos;

        final SpendAuthority wallet = wallets[shard];
        if (wallet.leaseId() == Lease.NONE) {
            return;
        }
        final long incarnation = wallet.incarnation();
        final long leaseId = wallet.leaseId();
        final long spent = wallet.leaseSpentMicros();
        net.send(nodeOf(shard), AUTHORITY_NODE, "report s" + shard + " #" + leaseId, () ->
                authority.recordReport(shard, incarnation, leaseId, spent));
    }

    static int nodeOf(int shard) {
        return shard + 1;
    }

    SimNetwork network() {
        return net;
    }

    BudgetAuthority authority() {
        return authority;
    }

    Config config() {
        return config;
    }

    /** Ground truth: money actually committed, tallied by the harness. */
    long actualSpendMicros() {
        return actualSpendMicros;
    }

    long overspendMicros() {
        return Math.max(0L, actualSpendMicros - config.budgetMicros);
    }

    double deliveredFraction() {
        return (double) actualSpendMicros / config.budgetMicros;
    }

    double overspendFraction() {
        return (double) overspendMicros() / config.budgetMicros;
    }

    long servedRequests() {
        return servedRequests;
    }

    long refusedRequests() {
        return refusedRequests;
    }

    long restarts() {
        return restarts;
    }
}
