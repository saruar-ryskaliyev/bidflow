package io.bidflow.budget.sim;

import static org.assertj.core.api.Assertions.assertThat;

import io.bidflow.sim.NetworkConditions;
import io.bidflow.sim.Simulation;
import io.bidflow.sim.Trace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The central claim of the project: an advertiser is never charged more than its budget, no
 * matter what the network and the machines do.
 *
 * <p>Each test runs a whole deployment through simulated time and then compares the money
 * actually committed against the budget. The comparison uses the harness's own tally rather
 * than anything the authority or the shards report, so a bug that corrupted the accounting
 * could not also hide itself from the assertion.
 */
class BudgetSafetyTest {

    private static final long MILLIS = 1_000_000L;

    /** Long enough that demand roughly doubles the budget, so the limit is genuinely reached. */
    private static final long RUN_LENGTH = 3_000 * MILLIS;

    private static final int CHAOS_SEEDS = 200;

    @Test
    @DisplayName("spends up to the budget and no further on a healthy cluster")
    void healthyClusterRespectsTheBudget() {
        final BudgetCluster cluster = run(1L, NetworkConditions.lan());

        assertThat(cluster.overspendMicros()).isZero();
        assertThat(cluster.servedRequests()).isPositive();
        assertThat(cluster.refusedRequests())
                .as("demand should have outrun the budget, or this test proves nothing")
                .isPositive();
    }

    @Test
    @DisplayName("delivers nearly the whole budget when nothing is broken")
    void healthyClusterDeliversNearlyEverything() {
        final BudgetCluster cluster = run(1L, NetworkConditions.lan());

        // Auction-priced traffic leaves more in per-shard lease tails than the old synthetic-cost
        // harness did; on this default configuration delivery stabilises just above 95%.
        assertThat(cluster.deliveredFraction()).isGreaterThan(0.95);
    }

    @Test
    @DisplayName("never overspends on a network that loses, delays and duplicates messages")
    void hostileNetworkNeverCausesOverspend() {
        final BudgetCluster cluster = run(2L, NetworkConditions.hostile());

        assertThat(cluster.overspendMicros()).isZero();
        assertThat(cluster.network().droppedCount()).isPositive();
        assertThat(cluster.network().duplicatedCount())
                .as("duplicate grants are the case cumulative accounting exists for")
                .isPositive();
    }

    @Test
    @DisplayName("never overspends while shards keep crashing and restarting")
    void repeatedCrashesNeverCauseOverspend() {
        final Simulation sim = new Simulation(3L, Trace.disabled());
        final BudgetCluster cluster =
                new BudgetCluster(sim, BudgetClusterConfig.defaults(), NetworkConditions.lan());
        cluster.start();

        for (long at = 100 * MILLIS; at < RUN_LENGTH; at += 100 * MILLIS) {
            final int shard = (int) ((at / (100 * MILLIS)) % cluster.config().shardCount());
            sim.schedule(at, () -> cluster.restartShard(shard));
        }
        sim.runUntil(RUN_LENGTH);

        assertThat(cluster.restarts()).isGreaterThan(20);
        assertThat(cluster.overspendMicros()).isZero();
    }

    @Test
    @DisplayName("never overspends when shards disagree with the authority about the time")
    void clockSkewNeverCausesOverspend() {
        final BudgetClusterConfig config = BudgetClusterConfig.defaults();
        final long[] skew = new long[config.shardCount()];
        for (int shard = 0; shard < skew.length; shard++) {
            // Half a second of disagreement in both directions, far worse than reality.
            skew[shard] = (shard % 2 == 0 ? 1 : -1) * (long) (shard + 1) * 100 * MILLIS;
        }

        final Simulation sim = new Simulation(4L, Trace.disabled());
        final BudgetCluster cluster = new BudgetCluster(sim, config, NetworkConditions.wan(), skew);
        cluster.start();
        sim.runUntil(RUN_LENGTH);

        // Safety here does not depend on clocks at all, which is the property worth having.
        // Clocks only decide how often a shard asks for money, never how much it may spend.
        assertThat(cluster.overspendMicros()).isZero();
    }

    @Test
    @DisplayName("never overspends when a shard is cut off from the authority entirely")
    void permanentPartitionNeverCausesOverspend() {
        final Simulation sim = new Simulation(5L, Trace.disabled());
        final BudgetCluster cluster =
                new BudgetCluster(sim, BudgetClusterConfig.defaults(), NetworkConditions.lan());
        cluster.start();

        sim.schedule(200 * MILLIS, () -> {
            cluster.network().partition(BudgetCluster.AUTHORITY_NODE, BudgetCluster.nodeOf(0));
            cluster.network().partition(BudgetCluster.AUTHORITY_NODE, BudgetCluster.nodeOf(1));
        });
        sim.runUntil(RUN_LENGTH);

        assertThat(cluster.overspendMicros()).isZero();
        // The isolated shards spend what they hold and then stop, which is the correct
        // failure mode: a shard that cannot reach the bank must not invent authority.
        assertThat(cluster.network().partitionedCount()).isPositive();
    }

    @Test
    @DisplayName("never overspends across " + CHAOS_SEEDS + " seeds of randomised faults")
    void chaosNeverCausesOverspend() {
        long restartsSeen = 0;
        long partitionedMessages = 0;
        long droppedMessages = 0;
        long duplicatedMessages = 0;
        double worstDelivery = 1.0;
        double totalDelivery = 0.0;
        long worstOverspend = 0;

        for (long seed = 1; seed <= CHAOS_SEEDS; seed++) {
            final BudgetCluster cluster = runChaos(seed);

            assertThat(cluster.overspendMicros()).as("seed %d overspent", seed).isZero();
            assertThat(committedMicros(cluster))
                    .as("seed %d granted more than the budget", seed)
                    .isLessThanOrEqualTo(cluster.config().budgetMicros());

            restartsSeen += cluster.restarts();
            partitionedMessages += cluster.network().partitionedCount();
            droppedMessages += cluster.network().droppedCount();
            duplicatedMessages += cluster.network().duplicatedCount();
            worstOverspend = Math.max(worstOverspend, cluster.overspendMicros());
            worstDelivery = Math.min(worstDelivery, cluster.deliveredFraction());
            totalDelivery += cluster.deliveredFraction();
        }

        assertThat(worstOverspend).isZero();

        // Guards against a sweep that passes because it never actually broke anything.
        assertThat(restartsSeen).as("the sweep must exercise crashes").isGreaterThan(100L);
        assertThat(worstDelivery).as("the sweep must actually spend money").isGreaterThan(0.0);

        // The sweep is the project's headline result, so it states what it actually did rather
        // than merely passing silently. A green test that proves nothing looks identical to a
        // green test that proves everything.
        System.out.printf(
                "chaos sweep: %d seeds, worst overspend %d micros, delivery mean %.4f worst %.4f, "
                        + "%d restarts, %d messages partitioned, %d dropped, %d duplicated%n",
                CHAOS_SEEDS, worstOverspend, totalDelivery / CHAOS_SEEDS, worstDelivery,
                restartsSeen, partitionedMessages, droppedMessages, duplicatedMessages);
    }

    @Test
    @DisplayName("crashes strand budget, which is the price paid for unconditional safety")
    void crashesStrandBudget() {
        final Simulation sim = new Simulation(6L, Trace.disabled());
        final BudgetCluster cluster = new BudgetCluster(
                sim, BudgetClusterConfig.defaults().withTopUpMicros(500_000L), NetworkConditions.lan());
        cluster.start();

        for (long at = 50 * MILLIS; at < RUN_LENGTH; at += 50 * MILLIS) {
            final int shard = (int) ((at / (50 * MILLIS)) % cluster.config().shardCount());
            sim.schedule(at, () -> cluster.restartShard(shard));
        }
        sim.runUntil(RUN_LENGTH);

        // A dying process takes its wallet with it. The authority cannot know how much of that
        // wallet was already spent, so it must assume all of it was and write the rest off.
        // That write-off is real money the advertiser wanted to spend and could not.
        assertThat(strandedByRestartsMicros(cluster)).isPositive();
        assertThat(cluster.overspendMicros()).isZero();
        assertThat(cluster.deliveredFraction())
                .as("frequent crashes should measurably hurt delivery")
                .isLessThan(0.99);
    }

    /** Settled plus outstanding is the authority's committed budget; it must never exceed the cap. */
    private static long committedMicros(BudgetCluster cluster) {
        return cluster.authority().settledMicros() + cluster.authority().outstandingMicros();
    }

    /**
     * Budget allocated to dead or unreachable shards: committed authority minus what the harness
     * actually spent and minus headroom still available to grant.
     */
    private static long strandedByRestartsMicros(BudgetCluster cluster) {
        return committedMicros(cluster)
                - cluster.actualSpendMicros()
                - cluster.authority().headroomMicros();
    }

    private static BudgetCluster run(long seed, NetworkConditions conditions) {
        final Simulation sim = new Simulation(seed, Trace.disabled());
        final BudgetCluster cluster = new BudgetCluster(sim, BudgetClusterConfig.defaults(), conditions);
        cluster.start();
        sim.runUntil(RUN_LENGTH);
        return cluster;
    }

    /**
     * One randomised deployment and fault schedule, drawn entirely from the seed so that any
     * failure replays from that single number.
     */
    private static BudgetCluster runChaos(long seed) {
        final Simulation sim = new Simulation(seed, Trace.disabled());

        final int shards = 2 + sim.random().nextInt(15);
        final BudgetClusterConfig config = BudgetClusterConfig.defaults()
                .withShardCount(shards)
                .withTopUpMicros(50_000L + sim.random().nextLong(450_001L));

        final long[] skew = new long[shards];
        for (int shard = 0; shard < shards; shard++) {
            skew[shard] = sim.random().nextLong(-250 * MILLIS, 250 * MILLIS + 1);
        }

        final NetworkConditions conditions = switch (sim.random().nextInt(3)) {
            case 0 -> NetworkConditions.lan();
            case 1 -> NetworkConditions.wan();
            default -> NetworkConditions.hostile();
        };

        final BudgetCluster cluster = new BudgetCluster(sim, config, conditions, skew);
        cluster.start();

        final int faults = 5 + sim.random().nextInt(20);
        for (int i = 0; i < faults; i++) {
            final long at = sim.random().nextLong(RUN_LENGTH);
            final int shard = sim.random().nextInt(shards);
            final int node = BudgetCluster.nodeOf(shard);
            switch (sim.random().nextInt(3)) {
                case 0 -> sim.schedule(at, () -> cluster.restartShard(shard));
                case 1 -> sim.schedule(at, () -> cluster.network().partition(BudgetCluster.AUTHORITY_NODE, node));
                default -> sim.schedule(at, () -> cluster.network().heal(BudgetCluster.AUTHORITY_NODE, node));
            }
        }

        sim.runUntil(RUN_LENGTH);
        return cluster;
    }
}
