package io.bidflow.sim;

import java.util.Arrays;

/**
 * A message network that loses, delays, duplicates, reorders, and partitions.
 *
 * <p>Messages are closures rather than serialized bytes: inside a single-threaded
 * simulation there is nothing to serialize for, and a closure keeps the calling code
 * readable. What matters is that delivery is <em>scheduled</em> rather than immediate, so
 * the sender keeps running while the message is in flight — which is where the interesting
 * interleavings live.
 *
 * <p>Reordering is emergent. Latency is drawn independently per message, so two messages
 * sent in order arrive out of order whenever the second draws a shorter delay than the
 * first. There is no separate reordering knob because there does not need to be.
 *
 * <p>Randomness is drawn in the same fixed order for every send — loss, then latency, then
 * duplication, then the duplicate's latency — even when a probability is zero and the draw
 * cannot change the outcome. Skipping unused draws would make the random stream depend on
 * the configuration, so two runs of the same seed under slightly different conditions would
 * diverge for reasons unrelated to the change being tested.
 */
public final class SimNetwork {

    /** Receives a message that has arrived. */
    public interface Delivery {
        void deliver();
    }

    private final Simulation simulation;
    private final NetworkConditions conditions;
    private final NetworkObserver observer;
    private final int nodeCount;
    private final boolean[] blocked;

    private long sent;
    private long delivered;
    private long dropped;
    private long duplicated;
    private long partitionedAway;

    public SimNetwork(Simulation simulation, int nodeCount, NetworkConditions conditions) {
        this(simulation, nodeCount, conditions, NetworkObserver.noop());
    }

    public SimNetwork(
            Simulation simulation,
            int nodeCount,
            NetworkConditions conditions,
            NetworkObserver observer) {
        if (nodeCount <= 0) {
            throw new IllegalArgumentException("nodeCount must be positive, was " + nodeCount);
        }
        if (observer == null) {
            throw new IllegalArgumentException("observer must not be null");
        }
        this.simulation = simulation;
        this.conditions = conditions;
        this.observer = observer;
        this.nodeCount = nodeCount;
        this.blocked = new boolean[nodeCount * nodeCount];
    }

    /**
     * Sends a message, which may be delayed, lost, duplicated, or discarded by a partition.
     *
     * @param label appears in the trace; make it identifying, since it is what a
     *     determinism mismatch will be diagnosed from
     */
    public void send(int from, int to, String label, Delivery delivery) {
        checkNode(from, "from");
        checkNode(to, "to");
        sent++;
        observe(NetworkEvent.send(simulation.now(), from, to, label));

        final boolean lost = simulation.random().nextDouble() < conditions.dropProbability();
        final long latency = drawLatency();
        final boolean duplicate = simulation.random().nextDouble() < conditions.duplicateProbability();
        final long duplicateLatency = drawLatency();

        // The partition is checked at send time, matching a network that refuses the
        // connection. A partition healing while a message is in flight does not resurrect it.
        if (blocked[from * nodeCount + to]) {
            partitionedAway++;
            simulation.log("blocked " + label + " " + from + "->" + to);
            observe(NetworkEvent.block(simulation.now(), from, to, label));
            return;
        }
        if (lost) {
            dropped++;
            simulation.log("dropped " + label + " " + from + "->" + to);
            observe(NetworkEvent.drop(simulation.now(), from, to, label));
            return;
        }
        schedule(from, to, label, delivery, latency, false);
        if (duplicate) {
            duplicated++;
            observe(NetworkEvent.duplicate(simulation.now(), from, to, label));
            schedule(from, to, label, delivery, duplicateLatency, true);
        }
    }

    private void schedule(int from, int to, String label, Delivery delivery, long latency, boolean copy) {
        simulation.schedule(latency, to, () -> {
            delivered++;
            simulation.log("recv " + label + " " + from + "->" + to + (copy ? " (duplicate)" : ""));
            observe(NetworkEvent.deliver(simulation.now(), from, to, label, copy));
            delivery.deliver();
        });
    }

    private long drawLatency() {
        final long span = conditions.maxLatencyNanos() - conditions.minLatencyNanos();
        return conditions.minLatencyNanos() + (span == 0 ? 0 : simulation.random().nextLong(span + 1));
    }

    /** Blocks traffic in both directions between two nodes. */
    public void partition(int a, int b) {
        partitionOneWay(a, b);
        partitionOneWay(b, a);
    }

    /**
     * Blocks traffic in one direction only.
     *
     * <p>Asymmetric partitions are worth testing on their own: a node that can send but not
     * receive still looks alive to everyone else, which defeats failure detectors that
     * assume reachability is symmetric.
     */
    public void partitionOneWay(int from, int to) {
        checkNode(from, "from");
        checkNode(to, "to");
        blocked[from * nodeCount + to] = true;
        simulation.log("partition " + from + "->" + to);
        observe(NetworkEvent.partition(simulation.now(), from, to));
    }

    /** Cuts a node off from every other node in both directions. */
    public void isolate(int node) {
        checkNode(node, "node");
        for (int other = 0; other < nodeCount; other++) {
            if (other != node) {
                partition(node, other);
            }
        }
    }

    public void heal(int a, int b) {
        checkNode(a, "a");
        checkNode(b, "b");
        blocked[a * nodeCount + b] = false;
        blocked[b * nodeCount + a] = false;
        simulation.log("heal " + a + "<->" + b);
        observe(NetworkEvent.heal(simulation.now(), a, b));
    }

    public void healAll() {
        Arrays.fill(blocked, false);
        simulation.log("heal all");
        observe(NetworkEvent.heal(simulation.now(), -1, -1));
    }

    public boolean isBlocked(int from, int to) {
        checkNode(from, "from");
        checkNode(to, "to");
        return blocked[from * nodeCount + to];
    }

    public int nodeCount() {
        return nodeCount;
    }

    public NetworkConditions conditions() {
        return conditions;
    }

    public long sentCount() {
        return sent;
    }

    /** Counts each copy of a duplicated message separately. */
    public long deliveredCount() {
        return delivered;
    }

    public long droppedCount() {
        return dropped;
    }

    public long duplicatedCount() {
        return duplicated;
    }

    public long partitionedCount() {
        return partitionedAway;
    }

    private void observe(NetworkEvent event) {
        observer.onEvent(event);
    }

    private void checkNode(int node, String name) {
        if (node < 0 || node >= nodeCount) {
            throw new IllegalArgumentException(
                    name + " must be in [0, " + nodeCount + "), was " + node);
        }
    }
}
