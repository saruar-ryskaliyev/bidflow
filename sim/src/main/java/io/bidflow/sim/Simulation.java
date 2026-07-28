package io.bidflow.sim;

import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.random.RandomGenerator;

/**
 * A single-threaded, seeded, virtual-time event loop — the substrate every other component
 * in this project is tested on.
 *
 * <h2>Why simulate at all</h2>
 *
 * <p>The bugs that matter in distributed budget enforcement only appear at specific
 * interleavings: a lease expiring while a reclaim message is still in flight, a shard
 * crashing between spending and reporting, two nodes disagreeing about whether a deadline
 * has passed. Reproducing those against real threads and real clocks is a matter of luck,
 * and luck does not survive a bug report. Here the entire schedule is explicit, so a
 * scenario that failed once fails identically forever.
 *
 * <p>This is the approach FoundationDB and TigerBeetle use: a failing run is fully
 * described by its seed, so a counterexample is a single number rather than a story about
 * timing.
 *
 * <h2>What determinism requires</h2>
 *
 * <p>Three rules, each of which is a bug if broken.
 *
 * <p><b>One thread.</b> Concurrency is modelled by interleaving events, never by running
 * them. Real threads would reintroduce exactly the nondeterminism being eliminated, and the
 * simulation would silently stop proving anything.
 *
 * <p><b>A total order on events.</b> Ties in scheduled time are broken by insertion
 * sequence, giving simultaneous events FIFO semantics. This is worth being precise about,
 * because it is easy to claim more for it than it delivers: {@link PriorityQueue} is an
 * array-backed binary heap, so it already resolves equal keys reproducibly, and dropping
 * the tiebreak does not by itself make a run irreproducible. What it does is make the
 * ordering arbitrary and fragile — simultaneous events would fire in heap order, which
 * shifts when unrelated events are added elsewhere in a scenario, so a recorded
 * counterexample would rot for reasons unconnected to the change under test.
 *
 * <p><b>One source of randomness, drawn in a fixed order.</b> Every random decision comes
 * from {@link #random()}. {@link Random} is used rather than a faster generator because its
 * algorithm is specified exactly, so a seed reproduces across JVM vendors and versions and
 * a recorded counterexample stays valid.
 *
 * <p>Time only advances when the queue does. A simulated day costs however long the work
 * takes, not a day.
 */
public final class Simulation {

    /** Events not tied to any node, and so never cancelled by a crash. */
    public static final int NO_OWNER = -1;

    /** Something to do at a simulated instant. */
    public interface Event {
        void fire();
    }

    /**
     * The next non-cancelled event waiting to fire, without the closure that would run it.
     *
     * @param timeNanos simulated instant at which the event fires
     * @param sequence insertion order among events scheduled at the same instant
     * @param owner node that owns the event, or {@link #NO_OWNER}
     */
    public record PendingEvent(long timeNanos, long sequence, int owner) {}

    private final PriorityQueue<Scheduled> pending = new PriorityQueue<>();
    private final Random random;
    private final Trace trace;
    private final long seed;
    private final long stepBudget;

    private long[] ownerEpoch = new long[8];
    private long now;
    private long sequence;
    private long steps;

    public Simulation(long seed) {
        this(seed, Trace.enabled(), 100_000_000L);
    }

    public Simulation(long seed, Trace trace) {
        this(seed, trace, 100_000_000L);
    }

    /**
     * @param seed reproduces the entire run; the only thing needed to replay a failure
     * @param trace event log, or {@link Trace#disabled()} for long runs
     * @param stepBudget events after which the run aborts, catching a scheduler that
     *     re-arms itself faster than time advances instead of hanging the test suite
     */
    public Simulation(long seed, Trace trace, long stepBudget) {
        if (stepBudget <= 0) {
            throw new IllegalArgumentException("stepBudget must be positive, was " + stepBudget);
        }
        this.seed = seed;
        this.random = new Random(seed);
        this.trace = trace;
        this.stepBudget = stepBudget;
    }

    /** The one number needed to replay this run exactly. */
    public long seed() {
        return seed;
    }

    /** True simulated time, as distinct from any node's skewed view of it. */
    public long now() {
        return now;
    }

    public RandomGenerator random() {
        return random;
    }

    public Trace trace() {
        return trace;
    }

    public long eventsFired() {
        return steps;
    }

    public long pendingEvents() {
        return pending.size();
    }

    /** Records a trace entry at the current instant. */
    public void log(String message) {
        trace.record(now, message);
    }

    public void schedule(long delayNanos, Event event) {
        schedule(delayNanos, NO_OWNER, event);
    }

    /**
     * Schedules an event owned by a node, so that {@link #crash(int)} discards it.
     *
     * @param owner non-negative node id, or {@link #NO_OWNER}
     */
    public void schedule(long delayNanos, int owner, Event event) {
        if (delayNanos < 0) {
            throw new IllegalArgumentException("delayNanos must not be negative, was " + delayNanos);
        }
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        final long epoch;
        if (owner == NO_OWNER) {
            epoch = 0L;
        } else {
            ensureOwner(owner);
            epoch = ownerEpoch[owner];
        }
        pending.add(new Scheduled(now + delayNanos, sequence++, owner, epoch, event));
    }

    /**
     * Discards every pending event belonging to a node, modelling process death.
     *
     * <p>A crashed process loses its timers, and forgetting that is a common way for a
     * simulated crash to be gentler than a real one: the node comes back and its old
     * callbacks fire against fresh state. Bumping an epoch invalidates them in constant
     * time instead of scanning the queue.
     */
    public void crash(int owner) {
        if (owner < 0) {
            throw new IllegalArgumentException("owner must be non-negative, was " + owner);
        }
        ensureOwner(owner);
        ownerEpoch[owner]++;
        trace.record(now, "crash node=" + owner);
    }

    /**
     * The next non-cancelled pending event, or empty when the queue has nothing left to do.
     *
     * <p>Cancelled events left behind by {@link #crash(int)} are discarded as a side effect
     * of peeking past them, matching the silent skip that {@link #step()} and
     * {@link #runUntil(long)} already perform.
     */
    public Optional<PendingEvent> peek() {
        final Scheduled next = nextLive();
        if (next == null) {
            return Optional.empty();
        }
        return Optional.of(new PendingEvent(next.time(), next.sequence(), next.owner()));
    }

    /**
     * Fires the next non-cancelled event.
     *
     * @return {@code true} if an event fired, {@code false} if the queue was empty
     */
    public boolean step() {
        final Scheduled next = nextLive();
        if (next == null) {
            return false;
        }
        pending.poll();
        fire(next);
        return true;
    }

    /**
     * Fires up to {@code count} non-cancelled events.
     *
     * @return how many events actually fired
     */
    public long step(long count) {
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative, was " + count);
        }
        long fired = 0L;
        while (fired < count && step()) {
            fired++;
        }
        return fired;
    }

    /** Runs until the queue empties. */
    public void run() {
        runUntil(Long.MAX_VALUE);
    }

    /**
     * Runs every event scheduled at or before {@code deadlineNanos}, then leaves the clock
     * at the deadline so that a quiet period still advances time.
     */
    public void runUntil(long deadlineNanos) {
        while (true) {
            final Scheduled next = nextLive();
            if (next == null || next.time() > deadlineNanos) {
                break;
            }
            pending.poll();
            fire(next);
        }
        if (deadlineNanos != Long.MAX_VALUE && deadlineNanos > now) {
            now = deadlineNanos;
        }
    }

    /** Drops cancelled heads until a live event remains, or the queue is empty. */
    private Scheduled nextLive() {
        while (!pending.isEmpty()) {
            final Scheduled head = pending.peek();
            if (!isCancelled(head)) {
                return head;
            }
            pending.poll();
        }
        return null;
    }

    private void fire(Scheduled next) {
        if (++steps > stepBudget) {
            throw new IllegalStateException(
                    "step budget of " + stepBudget + " exhausted at " + next.time()
                            + "ns; a recurring event is probably re-arming with zero delay");
        }
        now = next.time();
        next.event().fire();
    }

    private boolean isCancelled(Scheduled scheduled) {
        return scheduled.owner() != NO_OWNER && scheduled.epoch() != ownerEpoch[scheduled.owner()];
    }

    private void ensureOwner(int owner) {
        if (owner >= ownerEpoch.length) {
            final long[] grown = new long[Math.max(owner + 1, ownerEpoch.length * 2)];
            System.arraycopy(ownerEpoch, 0, grown, 0, ownerEpoch.length);
            ownerEpoch = grown;
        }
    }

    private record Scheduled(long time, long sequence, int owner, long epoch, Event event)
            implements Comparable<Scheduled> {

        @Override
        public int compareTo(Scheduled other) {
            final int byTime = Long.compare(time, other.time);
            // Insertion order breaks ties. A binary heap already resolves equal keys
            // reproducibly, so this is not what makes the run deterministic; it is what
            // makes the ordering meaningful and stable under unrelated edits.
            return byTime != 0 ? byTime : Long.compare(sequence, other.sequence);
        }
    }
}
