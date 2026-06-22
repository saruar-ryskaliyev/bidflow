package io.bidflow.sim;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The test the whole harness stands on.
 *
 * <p>Every later claim about budget safety takes the form "seed 8,472 spent 1.0003 times the
 * budget", and such a claim means nothing unless seed 8,472 reproduces. So this exercises the
 * harness against a deliberately messy scenario — five nodes gossiping over a hostile
 * network, a partition opening and healing, a node crashing and restarting — and checks that
 * two runs agree exactly.
 *
 * <p>The scenario is built to be hard to make deterministic. It draws randomness for send
 * intervals, peer choice, packet loss, duplication, and latency, and it crashes a node while
 * its messages are in flight. If any of the usual reproducibility leaks were present — a real
 * clock, hash-ordered iteration, an unstable tiebreak among simultaneous events — these
 * assertions would catch it.
 */
class DeterminismTest {

    private static final long MILLIS = 1_000_000L;
    private static final long RUN_LENGTH = 1_000 * MILLIS;
    private static final int NODES = 5;

    @Test
    @DisplayName("the same seed reproduces the run exactly")
    void sameSeedReproducesTheRun() {
        final Simulation first = scenario(8_472L, Trace.enabled());
        final Simulation second = scenario(8_472L, Trace.enabled());

        // Guard against a vacuous pass: two empty traces would also match.
        assertThat(first.trace().entries()).hasSizeGreaterThan(500);

        assertThat(second.trace().digest()).isEqualTo(first.trace().digest());
        assertThat(second.trace().entries()).isEqualTo(first.trace().entries());
        assertThat(second.eventsFired()).isEqualTo(first.eventsFired());
        assertThat(second.now()).isEqualTo(first.now());
    }

    @Test
    @DisplayName("different seeds explore different histories")
    void differentSeedsDiverge() {
        final String one = scenario(8_472L, Trace.enabled()).trace().digest();
        final String two = scenario(19_937L, Trace.enabled()).trace().digest();

        // If seeds did not diverge, running thousands of them would explore one history.
        assertThat(two).isNotEqualTo(one);
    }

    @Test
    @DisplayName("turning tracing off does not change what happens")
    void observabilityDoesNotPerturbTheRun() {
        final Simulation traced = scenario(8_472L, Trace.enabled());
        final Simulation untraced = scenario(8_472L, Trace.disabled());

        // Tracing must be pure observation. If recording drew from the random stream, the
        // system would behave differently when watched, and a long run with tracing off
        // would not reproduce the failure a short traced run found.
        assertThat(untraced.eventsFired()).isEqualTo(traced.eventsFired());
        assertThat(untraced.now()).isEqualTo(traced.now());
        assertThat(untraced.trace().entries()).isEmpty();
    }

    @Test
    @DisplayName("a run is reproducible across many seeds, not just a lucky one")
    void reproducibleAcrossManySeeds() {
        for (long seed = 1L; seed <= 25L; seed++) {
            final String first = scenario(seed, Trace.enabled()).trace().digest();
            final String second = scenario(seed, Trace.enabled()).trace().digest();
            assertThat(second).as("seed %d", seed).isEqualTo(first);
        }
    }

    /** Five nodes gossiping over a hostile network, with a partition and a crash. */
    private static Simulation scenario(long seed, Trace trace) {
        final Simulation sim = new Simulation(seed, trace);
        final SimNetwork net = new SimNetwork(sim, NODES, NetworkConditions.hostile());

        for (int node = 0; node < NODES; node++) {
            armGossip(sim, net, node);
        }
        sim.schedule(200 * MILLIS, () -> net.partition(1, 2));
        sim.schedule(400 * MILLIS, () -> net.heal(1, 2));
        sim.schedule(600 * MILLIS, () -> sim.crash(3));
        sim.schedule(700 * MILLIS, () -> armGossip(sim, net, 3));

        sim.runUntil(RUN_LENGTH);
        return sim;
    }

    private static void armGossip(Simulation sim, SimNetwork net, int node) {
        final long delay = MILLIS + sim.random().nextLong(9 * MILLIS);
        sim.schedule(delay, node, () -> {
            final int peer = sim.random().nextInt(net.nodeCount());
            if (peer != node) {
                net.send(node, peer, "gossip-" + node, () -> sim.log("ack@" + peer));
            }
            armGossip(sim, net, node);
        });
    }
}
