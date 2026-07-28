package io.bidflow.budget;

import static org.assertj.core.api.Assertions.assertThat;

import io.bidflow.sim.NetworkConditions;
import io.bidflow.sim.Simulation;
import io.bidflow.sim.Trace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pacing under front-loaded demand: without a controller the campaign burns budget early;
 * with one, cumulative spend stays nearer the linear target without breaking the reclaim
 * safety theorem.
 */
class BudgetPacingTest {

    private static final long MILLIS = 1_000_000L;
    private static final long RUN_LENGTH = 2_000 * MILLIS;
    private static final long BUDGET = 5_000_000L;
    private static final long CHECKPOINT = 100 * MILLIS;
    private static final long SEED = 7L;

    @Test
    @DisplayName("pacing lowers mid-run spend versus the same front-loaded traffic unpaced")
    void pacedSpendsLessByMiddayThanUnpaced() {
        final long unpacedMid = midSpend(null);
        final PacingController pacing = new PacingController(
                0L, RUN_LENGTH, BUDGET, 25_000, PacingController.BPS);
        final BudgetCluster paced = run(pacing);

        long pacedMid = 0;
        for (long[] point : paced.spendCheckpoints()) {
            if (point[0] >= RUN_LENGTH / 2) {
                pacedMid = point[1];
                break;
            }
        }

        assertThat(paced.overspendMicros()).isZero();
        assertThat(paced.authority().leasesPaced()).isPositive();
        assertThat(pacedMid)
                .as("paced mid=%d should be below unpaced mid=%d", pacedMid, unpacedMid)
                .isLessThan(unpacedMid);
        assertThat(paced.deliveredFraction())
                .as("pacing must not starve the campaign entirely")
                .isGreaterThan(0.25);
    }

    private static long midSpend(LeaseGrantPolicy policy) {
        final BudgetCluster cluster = run(policy);
        for (long[] point : cluster.spendCheckpoints()) {
            if (point[0] >= RUN_LENGTH / 2) {
                return point[1];
            }
        }
        return cluster.actualSpendMicros();
    }

    private static BudgetCluster run(LeaseGrantPolicy policy) {
        final BudgetCluster.Config config = new BudgetCluster.Config()
                .shardCount(4)
                .budgetMicros(BUDGET)
                .leaseMicros(100_000L)
                .leaseDurationNanos(200 * MILLIS)
                .reclaimMarginNanos(BudgetAuthority.NEVER_RECLAIM)
                .frontLoadedTraffic(true)
                .grantPolicy(policy)
                .checkpointIntervalNanos(CHECKPOINT)
                .minRequestIntervalNanos(200_000L)
                .maxRequestIntervalNanos(2_000_000L);

        final Simulation sim = new Simulation(SEED, Trace.disabled());
        final BudgetCluster cluster = new BudgetCluster(sim, config, NetworkConditions.lan());
        cluster.start(RUN_LENGTH);
        sim.runUntil(RUN_LENGTH);
        return cluster;
    }
}
