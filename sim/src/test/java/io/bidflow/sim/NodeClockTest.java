package io.bidflow.sim;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NodeClockTest {

    private static final long MILLIS = 1_000_000L;

    @Test
    @DisplayName("a fast clock reads ahead of true time and a slow one behind")
    void offsetsShiftTheNodesView() {
        final Simulation sim = new Simulation(1L, Trace.disabled());
        final NodeClock fast = new NodeClock(sim, 5 * MILLIS);
        final NodeClock slow = new NodeClock(sim, -3 * MILLIS);

        sim.schedule(100 * MILLIS, () -> {});
        sim.run();

        assertThat(sim.now()).isEqualTo(100 * MILLIS);
        assertThat(fast.nanos()).isEqualTo(105 * MILLIS);
        assertThat(slow.nanos()).isEqualTo(97 * MILLIS);
    }

    @Test
    @DisplayName("two nodes can disagree about whether a deadline has passed")
    void skewedNodesDisagreeAboutADeadline() {
        final Simulation sim = new Simulation(1L, Trace.disabled());
        final NodeClock granter = new NodeClock(sim, 0L);
        final NodeClock holder = new NodeClock(sim, -20 * MILLIS);
        final long deadline = 100 * MILLIS;

        sim.schedule(105 * MILLIS, () -> {});
        sim.run();

        // This disagreement is the whole reason lease expiry needs a safety margin: the
        // granter has already reclaimed the budget that the holder still believes is its own.
        assertThat(granter.nanos()).isGreaterThan(deadline);
        assertThat(holder.nanos()).isLessThan(deadline);
    }

    @Test
    @DisplayName("a clock correction can drag a node's time backwards")
    void correctionsCanMoveTimeBackwards() {
        final Simulation sim = new Simulation(1L, Trace.disabled());
        final NodeClock drifting = new NodeClock(sim, 50 * MILLIS);

        sim.schedule(100 * MILLIS, () -> {});
        sim.run();
        final long beforeCorrection = drifting.nanos();

        drifting.setOffsetNanos(0L);

        // Code that assumes time is monotonic breaks here, which is why this is worth
        // being able to inject rather than assume away.
        assertThat(drifting.nanos()).isLessThan(beforeCorrection);
        assertThat(drifting.nanos()).isEqualTo(sim.now());
    }

    @Test
    @DisplayName("reports its own error")
    void reportsItsOffset() {
        final Simulation sim = new Simulation(1L, Trace.disabled());
        assertThat(new NodeClock(sim, 7 * MILLIS).offsetNanos()).isEqualTo(7 * MILLIS);
    }
}
