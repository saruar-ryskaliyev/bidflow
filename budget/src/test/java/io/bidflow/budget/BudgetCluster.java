package io.bidflow.budget;

import io.bidflow.sim.NetworkConditions;
import io.bidflow.sim.NodeClock;
import io.bidflow.sim.SimNetwork;
import io.bidflow.sim.Simulation;

/**
 * A whole budget-enforcement deployment inside the simulator: one authority, many serving
 * shards, and traffic trying to spend money.
 *
 * <p>The authority is node 0 and shard {@code s} is node {@code s + 1}, so the simulator's
 * partition and crash controls address them directly.
 *
 * <p>The field that matters is {@link #actualSpendMicros()}. It is incremented here, by the
 * harness, every time a wallet actually commits a spend — never derived from anything the
 * authority or the shards believe. That independence is the point: if the accounting were read
 * back out of the components under test, a bug that corrupted the accounting would also
 * corrupt the measurement and the test would pass.
 */
final class BudgetCluster {

    static final int AUTHORITY_NODE = 0;

    record Config(
            int shardCount,
            long budgetMicros,
            long topUpMicros,
            long topUpThresholdMicros,
            long requestCooldownNanos,
            long minCostMicros,
            long maxCostMicros,
            long minRequestIntervalNanos,
            long maxRequestIntervalNanos) {

        Config {
            if (shardCount <= 0) {
                throw new IllegalArgumentException("shardCount must be positive, was " + shardCount);
            }
            if (minCostMicros < 0 || maxCostMicros < minCostMicros) {
                throw new IllegalArgumentException("cost range is invalid");
            }
            if (minRequestIntervalNanos <= 0 || maxRequestIntervalNanos < minRequestIntervalNanos) {
                throw new IllegalArgumentException("request interval range is invalid");
            }
        }

        /** Eight shards, a ten-unit budget, and enough demand to exhaust it well before the end. */
        static Config defaults() {
            return new Config(8, 10_000_000L, 200_000L, 40_000L, 5_000_000L, 100L, 900L, 500_000L, 1_500_000L);
        }

        Config withShardCount(int shardCount) {
            return new Config(
                    shardCount, budgetMicros, topUpMicros, topUpThresholdMicros, requestCooldownNanos,
                    minCostMicros, maxCostMicros, minRequestIntervalNanos, maxRequestIntervalNanos);
        }

        Config withBudgetMicros(long budgetMicros) {
            return new Config(
                    shardCount, budgetMicros, topUpMicros, topUpThresholdMicros, requestCooldownNanos,
                    minCostMicros, maxCostMicros, minRequestIntervalNanos, maxRequestIntervalNanos);
        }

        Config withTopUpMicros(long topUpMicros) {
            return new Config(
                    shardCount, budgetMicros, topUpMicros, topUpThresholdMicros, requestCooldownNanos,
                    minCostMicros, maxCostMicros, minRequestIntervalNanos, maxRequestIntervalNanos);
        }
    }

    private final Simulation sim;
    private final SimNetwork net;
    private final Config config;
    private final BudgetAuthority authority;
    private final SpendAuthority[] wallets;
    private final NodeClock[] clocks;
    private final long[] incarnations;
    private final long[] nextRequestAllowedAt;

    private long actualSpendMicros;
    private long servedRequests;
    private long refusedRequests;
    private long restarts;

    BudgetCluster(Simulation sim, Config config, NetworkConditions conditions) {
        this(sim, config, conditions, new long[config.shardCount()]);
    }

    BudgetCluster(Simulation sim, Config config, NetworkConditions conditions, long[] clockOffsetsNanos) {
        if (clockOffsetsNanos.length != config.shardCount()) {
            throw new IllegalArgumentException(
                    "expected " + config.shardCount() + " clock offsets, got " + clockOffsetsNanos.length);
        }
        this.sim = sim;
        this.config = config;
        this.net = new SimNetwork(sim, config.shardCount() + 1, conditions);
        this.authority = new BudgetAuthority(config.budgetMicros(), config.shardCount());
        this.wallets = new SpendAuthority[config.shardCount()];
        this.clocks = new NodeClock[config.shardCount()];
        this.incarnations = new long[config.shardCount()];
        this.nextRequestAllowedAt = new long[config.shardCount()];

        for (int shard = 0; shard < config.shardCount(); shard++) {
            incarnations[shard] = 1L;
            wallets[shard] = new SpendAuthority(shard, 1L);
            clocks[shard] = new NodeClock(sim, clockOffsetsNanos[shard]);
        }
    }

    /** Begins serving traffic on every shard. */
    void start() {
        for (int shard = 0; shard < config.shardCount(); shard++) {
            scheduleTraffic(shard);
        }
    }

    /**
     * Kills a shard's process and starts a replacement.
     *
     * <p>The wallet is deliberately discarded rather than carried over. It lived in memory, so
     * a real crash loses it, along with the record of what the dead process had spent.
     */
    void restartShard(int shard) {
        sim.crash(nodeOf(shard));
        incarnations[shard]++;
        wallets[shard] = new SpendAuthority(shard, incarnations[shard]);
        nextRequestAllowedAt[shard] = 0L;
        restarts++;
        scheduleTraffic(shard);
    }

    private void scheduleTraffic(int shard) {
        final long span = config.maxRequestIntervalNanos() - config.minRequestIntervalNanos();
        final long delay = config.minRequestIntervalNanos()
                + (span == 0 ? 0 : sim.random().nextLong(span + 1));
        sim.schedule(delay, nodeOf(shard), () -> {
            serveOne(shard);
            maybeRequestTopUp(shard);
            scheduleTraffic(shard);
        });
    }

    private void serveOne(int shard) {
        final long span = config.maxCostMicros() - config.minCostMicros();
        final long cost = config.minCostMicros() + (span == 0 ? 0 : sim.random().nextLong(span + 1));
        if (wallets[shard].tryReserve(cost)) {
            actualSpendMicros += cost;
            servedRequests++;
        } else {
            refusedRequests++;
        }
    }

    /**
     * Asks the authority for more money when the wallet runs low.
     *
     * <p>Rate-limited on the shard's own clock rather than tracked with an in-flight flag. A
     * flag would deadlock the shard forever if the reply were lost, whereas a cooldown retries
     * naturally on the next traffic tick. Using the shard's skewed clock is deliberate: real
     * shards have no access to true time.
     */
    private void maybeRequestTopUp(int shard) {
        if (!wallets[shard].needsTopUp(config.topUpThresholdMicros())) {
            return;
        }
        final long now = clocks[shard].nanos();
        if (now < nextRequestAllowedAt[shard]) {
            return;
        }
        nextRequestAllowedAt[shard] = now + config.requestCooldownNanos();

        // Captured at send time. The wallet may be replaced by a restart before this arrives,
        // which is exactly why the incarnation travels with the message.
        final long incarnation = wallets[shard].incarnation();
        final long spentSoFar = wallets[shard].spentMicros();

        net.send(nodeOf(shard), AUTHORITY_NODE, "topup s" + shard + " i" + incarnation, () -> {
            authority.recordReport(shard, incarnation, spentSoFar);
            final long total = authority.requestAuthority(shard, incarnation, config.topUpMicros());
            if (total == BudgetAuthority.SUPERSEDED) {
                return;
            }
            net.send(AUTHORITY_NODE, nodeOf(shard), "grant s" + shard + " i" + incarnation, () ->
                    wallets[shard].applyGrant(incarnation, total));
        });
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

    /** Ground truth: money actually committed, counted by the harness itself. */
    long actualSpendMicros() {
        return actualSpendMicros;
    }

    /** How much of the budget was delivered, as a fraction. */
    double deliveredFraction() {
        return (double) actualSpendMicros / config.budgetMicros();
    }

    /** Spend beyond the budget. Zero is the only acceptable value. */
    long overspendMicros() {
        return Math.max(0L, actualSpendMicros - config.budgetMicros());
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
