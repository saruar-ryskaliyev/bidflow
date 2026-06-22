package io.bidflow.sim;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SimNetworkTest {

    private static final long MILLIS = 1_000_000L;

    @Test
    @DisplayName("delivers a message after a delay drawn inside the configured bounds")
    void deliversWithinLatencyBounds() {
        final NetworkConditions conditions = new NetworkConditions(2 * MILLIS, 8 * MILLIS, 0.0, 0.0);
        final Simulation sim = new Simulation(42L, Trace.disabled());
        final SimNetwork net = new SimNetwork(sim, 2, conditions);
        final List<Long> arrivals = new ArrayList<>();

        for (int i = 0; i < 500; i++) {
            net.send(0, 1, "m", () -> arrivals.add(sim.now()));
        }
        sim.run();

        assertThat(arrivals).hasSize(500);
        assertThat(arrivals).allSatisfy(at -> assertThat(at).isBetween(2 * MILLIS, 8 * MILLIS));
    }

    @Test
    @DisplayName("delivers nothing instantly, so the sender keeps running while in flight")
    void deliveryIsNeverSynchronous() {
        final Simulation sim = new Simulation(1L, Trace.disabled());
        final SimNetwork net = new SimNetwork(sim, 2, NetworkConditions.lan());
        final List<String> order = new ArrayList<>();

        net.send(0, 1, "m", () -> order.add("received"));
        order.add("sender continued");
        sim.run();

        assertThat(order).containsExactly("sender continued", "received");
    }

    @Test
    @DisplayName("reorders messages when latency varies more than the gap between sends")
    void variableLatencyReordersMessages() {
        final NetworkConditions jittery = new NetworkConditions(0L, 50 * MILLIS, 0.0, 0.0);
        final Simulation sim = new Simulation(7L, Trace.disabled());
        final SimNetwork net = new SimNetwork(sim, 2, jittery);
        final List<Integer> arrivals = new ArrayList<>();

        for (int i = 0; i < 50; i++) {
            final int index = i;
            net.send(0, 1, "m" + i, () -> arrivals.add(index));
        }
        sim.run();

        final List<Integer> sendOrder = IntStream.range(0, 50).boxed().toList();
        assertThat(arrivals).hasSize(50).containsExactlyInAnyOrderElementsOf(sendOrder);
        assertThat(arrivals).isNotEqualTo(sendOrder);
    }

    @Test
    @DisplayName("a partition silently discards traffic in both directions")
    void partitionBlocksBothDirections() {
        final Simulation sim = new Simulation(1L, Trace.disabled());
        final SimNetwork net = new SimNetwork(sim, 3, NetworkConditions.perfect());
        final List<String> received = new ArrayList<>();

        net.partition(0, 1);
        net.send(0, 1, "forward", () -> received.add("0->1"));
        net.send(1, 0, "reverse", () -> received.add("1->0"));
        net.send(0, 2, "unaffected", () -> received.add("0->2"));
        sim.run();

        assertThat(received).containsExactly("0->2");
        assertThat(net.partitionedCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("a one-way partition leaves a node able to send but not receive")
    void oneWayPartitionIsAsymmetric() {
        final Simulation sim = new Simulation(1L, Trace.disabled());
        final SimNetwork net = new SimNetwork(sim, 2, NetworkConditions.perfect());
        final List<String> received = new ArrayList<>();

        net.partitionOneWay(0, 1);
        net.send(0, 1, "blocked", () -> received.add("0->1"));
        net.send(1, 0, "allowed", () -> received.add("1->0"));
        sim.run();

        // Node 0 still looks alive to node 1, which is what defeats a failure detector
        // that assumes reachability is symmetric.
        assertThat(received).containsExactly("1->0");
    }

    @Test
    @DisplayName("healing restores delivery")
    void healRestoresDelivery() {
        final Simulation sim = new Simulation(1L, Trace.disabled());
        final SimNetwork net = new SimNetwork(sim, 2, NetworkConditions.perfect());
        final List<String> received = new ArrayList<>();

        net.partition(0, 1);
        net.send(0, 1, "during", () -> received.add("during"));
        sim.schedule(10 * MILLIS, () -> {
            net.heal(0, 1);
            net.send(0, 1, "after", () -> received.add("after"));
        });
        sim.run();

        assertThat(received).containsExactly("after");
        assertThat(net.isBlocked(0, 1)).isFalse();
    }

    @Test
    @DisplayName("isolating a node cuts every one of its links")
    void isolateCutsAllLinks() {
        final Simulation sim = new Simulation(1L, Trace.disabled());
        final SimNetwork net = new SimNetwork(sim, 4, NetworkConditions.perfect());

        net.isolate(2);

        for (int other = 0; other < 4; other++) {
            if (other != 2) {
                assertThat(net.isBlocked(2, other)).isTrue();
                assertThat(net.isBlocked(other, 2)).isTrue();
            }
        }
        assertThat(net.isBlocked(0, 1)).isFalse();
    }

    @Test
    @DisplayName("drops messages at roughly the configured rate")
    void dropsAtTheConfiguredRate() {
        final NetworkConditions lossy = new NetworkConditions(MILLIS, MILLIS, 0.1, 0.0);
        final Simulation sim = new Simulation(99L, Trace.disabled());
        final SimNetwork net = new SimNetwork(sim, 2, lossy);

        for (int i = 0; i < 10_000; i++) {
            net.send(0, 1, "m", () -> {});
        }
        sim.run();

        assertThat(net.droppedCount()).isBetween(800L, 1_200L);
        assertThat(net.deliveredCount()).isEqualTo(10_000L - net.droppedCount());
    }

    @Test
    @DisplayName("duplicates arrive twice, each with its own latency")
    void duplicatesArriveTwice() {
        final NetworkConditions duplicating = new NetworkConditions(MILLIS, 20 * MILLIS, 0.0, 1.0);
        final Simulation sim = new Simulation(5L, Trace.disabled());
        final SimNetwork net = new SimNetwork(sim, 2, duplicating);
        final List<Long> arrivals = new ArrayList<>();

        net.send(0, 1, "m", () -> arrivals.add(sim.now()));
        sim.run();

        assertThat(arrivals).hasSize(2);
        assertThat(net.duplicatedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a message in flight to a node that crashes is never delivered")
    void inFlightMessagesDieWithTheReceiver() {
        final NetworkConditions slow = new NetworkConditions(50 * MILLIS, 50 * MILLIS, 0.0, 0.0);
        final Simulation sim = new Simulation(1L, Trace.disabled());
        final SimNetwork net = new SimNetwork(sim, 2, slow);
        final List<String> received = new ArrayList<>();

        net.send(0, 1, "m", () -> received.add("delivered"));
        sim.schedule(10 * MILLIS, () -> sim.crash(1));
        sim.run();

        assertThat(received).isEmpty();
    }

    @Test
    @DisplayName("counts what it did to the traffic")
    void tracksCounters() {
        final Simulation sim = new Simulation(3L, Trace.disabled());
        final SimNetwork net = new SimNetwork(sim, 2, NetworkConditions.perfect());

        for (int i = 0; i < 100; i++) {
            net.send(0, 1, "m", () -> {});
        }
        sim.run();

        assertThat(net.sentCount()).isEqualTo(100);
        assertThat(net.deliveredCount()).isEqualTo(100);
        assertThat(net.droppedCount()).isZero();
        assertThat(net.duplicatedCount()).isZero();
    }
}
