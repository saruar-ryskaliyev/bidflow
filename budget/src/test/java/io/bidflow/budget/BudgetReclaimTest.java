package io.bidflow.budget;

import static org.assertj.core.api.Assertions.assertThat;

import io.bidflow.sim.NetworkConditions;
import io.bidflow.sim.Simulation;
import io.bidflow.sim.Trace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What reclaiming stranded budget costs and what it buys.
 *
 * <h2>The theorem under test</h2>
 *
 * <p>Overspend can never exceed the total the authority reclaimed unilaterally.
 *
 * <p>Every micro of overspend comes from the authority settling a lease below what was actually
 * spent on it and re-leasing the difference. That difference is precisely the reclaimed portion
 * of that lease, so summing over all leases bounds total overspend by total reclaim. Two
 * consequences follow, and they are the two ends of the trade-off: reclaim nothing and overspend
 * is exactly zero, which is the unconditionally safe configuration; reclaim aggressively and the
 * exposure is whatever was reclaimed.
 *
 * <h2>Why the margin alone is not enough</h2>
 *
 * <p>Two independent things can go wrong when the authority takes a lease back without its
 * holder's cooperation.
 *
 * <p>The holder may still be spending. It stops when its own clock passes the expiry; the
 * authority reclaims when its clock passes expiry plus the margin. A margin below the clock
 * disagreement leaves a window where both believe they own the money, and a margin above it
 * closes that window.
 *
 * <p>Separately, the authority settles at the last figure it <em>heard</em>. A shard that was
 * partitioned away stopped reporting long before it stopped spending, so its known figure can be
 * far below the truth — and no margin fixes that, because waiting longer does not produce a
 * report that was never delivered. This is why lease size, not the margin, is the real control
 * on the exposure: it caps what a single unreachable lease can cost.
 */
class BudgetReclaimTest {

    private static final long MILLIS = 1_000_000L;
    private static final long RUN_LENGTH = 3_000 * MILLIS;

    /** How far behind the authority every shard's clock runs in the margin experiments. */
    private static final long SKEW = 150 * MILLIS;

    private static final int SEEDS_PER_POINT = 12;
    private static final int CHAOS_SEEDS = 120;

    private static final long LEASE_DURATION = 100 * MILLIS;

    /** Long enough to outlast any run, reproducing authority that never expires. */
    private static final long NO_EXPIRY = 1_000_000 * MILLIS;

    @Test
    @DisplayName("declining to reclaim never overspends, at any cost in delivery")
    void noReclaimNeverOverspends() {
        for (long seed = 1; seed <= 20; seed++) {
            final BudgetCluster cluster = runChaos(seed, BudgetAuthority.NEVER_RECLAIM, 200_000L);

            assertThat(cluster.overspendMicros()).as("seed %d", seed).isZero();
            assertThat(cluster.authority().reclaimedMicros()).isZero();
        }
    }

    @Test
    @DisplayName("overspend never exceeds what was reclaimed, across every fault mix")
    void overspendIsBoundedByReclaim() {
        long worstOverspend = 0;
        long totalReclaimed = 0;

        for (long seed = 1; seed <= CHAOS_SEEDS; seed++) {
            final BudgetCluster cluster = runChaos(seed, 50 * MILLIS, 200_000L);

            // The theorem. If this ever fails, overspend has a source other than optimistic
            // reclaim and the whole safety story needs rewriting.
            assertThat(cluster.overspendMicros())
                    .as("seed %d overspent more than it reclaimed", seed)
                    .isLessThanOrEqualTo(cluster.authority().reclaimedMicros());

            worstOverspend = Math.max(worstOverspend, cluster.overspendMicros());
            totalReclaimed += cluster.authority().reclaimedMicros();
        }

        // Guard against a vacuous pass: if nothing were ever reclaimed the bound would hold
        // trivially and prove nothing.
        assertThat(totalReclaimed).as("the sweep must actually reclaim budget").isPositive();
        assertThat(worstOverspend).as("worst overspend seen").isNotNegative();
    }

    @Test
    @DisplayName("reclaiming recovers delivery that the safe configuration leaves on the table")
    void reclaimRecoversDelivery() {
        double safeDelivery = 0;
        double reclaimingDelivery = 0;

        for (long seed = 1; seed <= SEEDS_PER_POINT; seed++) {
            safeDelivery += runChaos(seed, BudgetAuthority.NEVER_RECLAIM, 200_000L).deliveredFraction();
            reclaimingDelivery += runChaos(seed, 50 * MILLIS, 200_000L).deliveredFraction();
        }
        safeDelivery /= SEEDS_PER_POINT;
        reclaimingDelivery /= SEEDS_PER_POINT;

        // The entire point of the exercise. If reclaim did not measurably improve delivery there
        // would be no reason to accept any overspend risk for it.
        assertThat(reclaimingDelivery).isGreaterThan(safeDelivery);
    }

    @Test
    @DisplayName("a margin covering the clock disagreement reduces overspend")
    void largerMarginReducesOverspend() {
        final long impatient = averageOverspendFraction(0L);
        final long patient = averageOverspendFraction(2 * SKEW);

        // Every shard's clock runs SKEW behind here, so a zero margin guarantees an overlap in
        // which the authority and the holder both believe they own the money.
        assertThat(patient).isLessThan(impatient);
    }

    @Test
    @DisplayName("smaller leases cap the damage a single unreachable shard can do")
    void smallerLeasesReduceOverspend() {
        long large = 0;
        long small = 0;
        for (long seed = 1; seed <= SEEDS_PER_POINT; seed++) {
            large += runSkewed(seed, 0L, 800_000L).overspendMicros();
            small += runSkewed(seed, 0L, 50_000L).overspendMicros();
        }

        // The margin cannot help with spend that was never reported. Lease size can, because it
        // is the ceiling on what one lost lease is worth.
        assertThat(small).isLessThan(large);
    }

    /**
     * Isolates the clock-disagreement effect: a healthy network, frequent reports, no faults, and
     * every shard's clock running {@link #SKEW} behind the authority.
     *
     * <p>A margin under the skew takes leases back from shards that are still spending on them,
     * and overspend follows. A margin covering the skew eliminates that clock overspend — but not
     * quite all overspend, and the residue is itself a finding. Under the old seal-then-ask
     * renewal a shard fell silent one round trip before every renewal, so at the budget's
     * exhaustion tail — when the authority had nothing left to grant — wallets were already
     * sealed and the sweeper found nothing to take. Prefetch keeps those wallets spending their
     * live lease right up to their own view of its expiry, so the sweeper settles the tail
     * leases at their last report, and whatever was spent since that report is freed and
     * re-leased. That residue is bounded by report lag, not by clock skew: one report interval
     * of spending per shard, a fraction of a percent here, and always inside the
     * overspend-is-bounded-by-reclaim theorem.
     *
     * <p>The crossing between the regimes still sits below the skew, because a shard asks for
     * its next lease a little before expiry and stops spending the old lease the moment the
     * grant lands. The assertions check either side of the crossing rather than pinning its
     * exact location, which depends on the renewal lead, latency, and the sweep interval.
     */
    @Test
    @DisplayName("experiment 1: overspend appears when the margin is under the clock skew")
    void marginVersusClockSkew() {
        final long[] margins = {0L, 50 * MILLIS, 100 * MILLIS, SKEW, 2 * SKEW};

        System.out.printf("%n  experiment 1 — clock skew %dms, no faults%n", SKEW / MILLIS);
        System.out.printf("  %-10s %10s %11s %11s%n", "margin", "spent", "overspend", "reclaimed");

        for (long margin : margins) {
            final Averages averages = average(seed -> runSkewed(seed, margin, 200_000L));
            System.out.printf(
                    "  %-10s %9.2f%% %10.3f%% %10.2f%%%n",
                    margin / MILLIS + "ms", averages.spent * 100, averages.overspend * 100,
                    averages.reclaimed * 100);

            if (margin >= SKEW) {
                // Past the skew the authority cannot take money from a shard still spending
                // it. What remains is the exhaustion-tail residue described above, bounded
                // by one report interval of spending — far under half a percent here.
                assertThat(averages.overspend)
                        .as("margin %dms covers the skew", margin / MILLIS)
                        .isLessThan(0.005);
            } else if (margin <= 100 * MILLIS) {
                // Comfortably inside the crossing, so the overlap is real and money is taken from
                // shards that go on spending it.
                assertThat(averages.overspend).as("margin %dms is under the skew", margin / MILLIS).isPositive();
            }
        }
    }

    /**
     * The trade-off that actually matters: crashes and partitions strand budget, and reclaiming it
     * is the only way to get it back.
     *
     * <p>Here a patient margin is not free — it forgoes recovery — and an impatient one is not
     * safe. The curve is the deliverable; no single configuration on it is "the answer".
     */
    @Test
    @DisplayName("experiment 2: the trade-off curve under crashes and partitions")
    void reclaimTradeOffCurveUnderFaults() {
        final long[] margins = {
            BudgetAuthority.NEVER_RECLAIM, 500 * MILLIS, 200 * MILLIS, 100 * MILLIS, 50 * MILLIS, 0L
        };

        System.out.printf("%n  experiment 2 — randomised crashes and partitions, skew +/-50ms%n");
        System.out.printf("  %-10s %10s %11s %11s %8s%n", "config", "delivered", "overspend", "reclaimed", "bound");

        // The design that preceded reclaim: authority that never expires and is never taken back.
        // Measured under this exact fault mix, because comparing against a figure from a different
        // scenario would prove nothing about whether expiry was worth introducing.
        final Averages noExpiry = average(seed -> runChaos(seed, BudgetAuthority.NEVER_RECLAIM, 200_000L, NO_EXPIRY));
        System.out.printf(
                "  %-10s %9.2f%% %10.3f%% %10.2f%% %8s%n",
                "no expiry", Math.min(noExpiry.spent, 1.0) * 100, noExpiry.overspend * 100,
                noExpiry.reclaimed * 100, noExpiry.boundHolds ? "held" : "BROKEN");
        assertThat(noExpiry.overspend).as("the original design must not overspend").isZero();

        double safeDelivery = -1;
        double bestDelivery = 0;

        for (long margin : margins) {
            final Averages averages = average(seed -> runChaos(seed, margin, 200_000L, LEASE_DURATION));
            System.out.printf(
                    "  %-10s %9.2f%% %10.3f%% %10.2f%% %8s%n",
                    margin == BudgetAuthority.NEVER_RECLAIM ? "never" : margin / MILLIS + "ms",
                    Math.min(averages.spent, 1.0) * 100, averages.overspend * 100,
                    averages.reclaimed * 100, averages.boundHolds ? "held" : "BROKEN");

            assertThat(averages.boundHolds).as("overspend stayed within reclaim at margin %d", margin).isTrue();
            if (margin == BudgetAuthority.NEVER_RECLAIM) {
                safeDelivery = averages.spent;
                assertThat(averages.overspend).as("the safe configuration must not overspend").isZero();
            } else {
                bestDelivery = Math.max(bestDelivery, Math.min(averages.spent, 1.0));
            }
        }

        // Reclaiming has to buy something, or there would be no reason to accept any risk for it.
        assertThat(bestDelivery).as("reclaim should recover delivery the safe setting leaves stranded")
                .isGreaterThan(safeDelivery);

        // Within this window, never-expiring leases deliver the most: a partitioned shard
        // keeps serving from its wallet, while an expiring lease dies and its shard goes
        // dark until the partition heals. Reclaim claws back much of that gap by
        // re-leasing recovered money to shards that are reachable — money moves, serving
        // time does not. What no-expiry cannot bound is its permanent loss per crash,
        // invisible in three seconds and unbounded over a day. The curve prices that
        // trade; what is asserted is what must hold at any horizon — reclaim beats expiry
        // alone, and the configurations that never reclaim never overspend.
        System.out.printf(
                "  -> best with reclaim %.2f%% vs no expiry %.2f%% vs expiry alone %.2f%%%n",
                bestDelivery * 100, Math.min(noExpiry.spent, 1.0) * 100, safeDelivery * 100);
    }

    private record Averages(double spent, double overspend, double reclaimed, boolean boundHolds) {}

    private static Averages average(java.util.function.LongFunction<BudgetCluster> run) {
        double spent = 0;
        double overspend = 0;
        double reclaimed = 0;
        boolean boundHolds = true;
        for (long seed = 1; seed <= SEEDS_PER_POINT; seed++) {
            final BudgetCluster cluster = run.apply(seed);
            spent += cluster.deliveredFraction();
            overspend += cluster.overspendFraction();
            reclaimed += (double) cluster.authority().reclaimedMicros() / cluster.config().budgetMicros;
            boundHolds &= cluster.overspendMicros() <= cluster.authority().reclaimedMicros();
        }
        return new Averages(
                spent / SEEDS_PER_POINT, overspend / SEEDS_PER_POINT, reclaimed / SEEDS_PER_POINT, boundHolds);
    }

    private static long averageOverspendFraction(long marginNanos) {
        long total = 0;
        for (long seed = 1; seed <= SEEDS_PER_POINT; seed++) {
            total += runSkewed(seed, marginNanos, 200_000L).overspendMicros();
        }
        return total;
    }

    /**
     * Every shard's clock runs {@link #SKEW} behind the authority, with a healthy network and
     * frequent reports, so that the clock-disagreement effect is isolated from report lag.
     */
    private static BudgetCluster runSkewed(long seed, long marginNanos, long leaseMicros) {
        final Simulation sim = new Simulation(seed, Trace.disabled());
        final BudgetCluster.Config config = new BudgetCluster.Config()
                .reclaimMarginNanos(marginNanos)
                .leaseMicros(leaseMicros)
                .leaseDurationNanos(100 * MILLIS);
        config.sweepIntervalNanos = 2 * MILLIS;
        config.reportIntervalNanos = 10 * MILLIS;

        final long[] skew = new long[config.shardCount];
        java.util.Arrays.fill(skew, -SKEW);

        final BudgetCluster cluster = new BudgetCluster(sim, config, NetworkConditions.lan(), skew);
        cluster.start();
        sim.runUntil(RUN_LENGTH);
        return cluster;
    }

    private static BudgetCluster runChaos(long seed, long marginNanos, long leaseMicros) {
        return runChaos(seed, marginNanos, leaseMicros, LEASE_DURATION);
    }

    /** A randomised deployment and fault schedule, drawn entirely from the seed. */
    private static BudgetCluster runChaos(
            long seed, long marginNanos, long leaseMicros, long leaseDurationNanos) {
        final Simulation sim = new Simulation(seed, Trace.disabled());

        final int shards = 2 + sim.random().nextInt(11);
        final BudgetCluster.Config config = new BudgetCluster.Config()
                .shardCount(shards)
                .reclaimMarginNanos(marginNanos)
                .leaseMicros(leaseMicros)
                .leaseDurationNanos(leaseDurationNanos);

        final long[] skew = new long[shards];
        for (int shard = 0; shard < shards; shard++) {
            skew[shard] = sim.random().nextLong(-50 * MILLIS, 50 * MILLIS + 1);
        }

        final NetworkConditions conditions = switch (sim.random().nextInt(3)) {
            case 0 -> NetworkConditions.lan();
            case 1 -> NetworkConditions.wan();
            default -> NetworkConditions.hostile();
        };

        final BudgetCluster cluster = new BudgetCluster(sim, config, conditions, skew);
        cluster.start();

        final int faults = 4 + sim.random().nextInt(16);
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
