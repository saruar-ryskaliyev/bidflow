package io.bidflow.sim;

/**
 * One node's view of time, offset from true simulated time by a fixed skew.
 *
 * <p>Nodes disagreeing about the current instant is the crux of the budget safety argument
 * rather than a detail. A serving shard holds spend authority that expires at some instant;
 * the authority that granted it reclaims the unspent remainder at what it believes is the
 * same instant. If the two disagree, there is a window in which both consider the budget
 * theirs, and the amount that can be double-spent in that window is exactly what the
 * overdelivery bound has to account for.
 *
 * <p>Giving each node a deliberately wrong clock therefore is not pessimism, it is the
 * test. A real deployment inherits the same problem, which is why Spanner had to build
 * TrueTime to bound the disagreement rather than wish it away.
 */
public final class NodeClock implements NanoClock {

    private final Simulation simulation;
    private long offsetNanos;

    /**
     * @param simulation source of true simulated time
     * @param offsetNanos this node's error; positive runs fast, negative runs slow
     */
    public NodeClock(Simulation simulation, long offsetNanos) {
        this.simulation = simulation;
        this.offsetNanos = offsetNanos;
    }

    @Override
    public long nanos() {
        return simulation.now() + offsetNanos;
    }

    /** How far this clock is from true simulated time. */
    public long offsetNanos() {
        return offsetNanos;
    }

    /**
     * Changes this node's clock error mid-run, modelling drift or a correction step.
     *
     * <p>A step correction is worth exercising specifically: code that assumes time only
     * moves forward breaks when NTP drags a clock backwards.
     */
    public void setOffsetNanos(long offsetNanos) {
        this.offsetNanos = offsetNanos;
    }
}
