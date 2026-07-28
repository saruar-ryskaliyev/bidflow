package io.bidflow.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SimulationTest {

    private static final long MILLIS = 1_000_000L;

    @Test
    @DisplayName("fires events in scheduled time order, not submission order")
    void firesInTimeOrder() {
        final Simulation sim = new Simulation(1L);
        final List<String> fired = new ArrayList<>();

        sim.schedule(30 * MILLIS, () -> fired.add("third"));
        sim.schedule(10 * MILLIS, () -> fired.add("first"));
        sim.schedule(20 * MILLIS, () -> fired.add("second"));
        sim.run();

        assertThat(fired).containsExactly("first", "second", "third");
    }

    @Test
    @DisplayName("breaks equal scheduled times by submission order")
    void breaksTiesBySubmissionOrder() {
        final Simulation sim = new Simulation(1L);
        final List<Integer> fired = new ArrayList<>();

        // Without a sequence tiebreak these could fire in any order, and the whole
        // simulation would stop being reproducible.
        for (int i = 0; i < 200; i++) {
            final int index = i;
            sim.schedule(5 * MILLIS, () -> fired.add(index));
        }
        sim.run();

        assertThat(fired).hasSize(200).isSorted();
    }

    @Test
    @DisplayName("exposes the current instant to the event being fired")
    void nowReflectsTheFiringEvent() {
        final Simulation sim = new Simulation(1L);
        final List<Long> instants = new ArrayList<>();

        sim.schedule(7 * MILLIS, () -> instants.add(sim.now()));
        sim.schedule(11 * MILLIS, () -> instants.add(sim.now()));
        sim.run();

        assertThat(instants).containsExactly(7 * MILLIS, 11 * MILLIS);
    }

    @Test
    @DisplayName("lets an event schedule further work")
    void eventsCanScheduleMoreEvents() {
        final Simulation sim = new Simulation(1L);
        final List<Long> ticks = new ArrayList<>();

        sim.schedule(MILLIS, new Simulation.Event() {
            @Override
            public void fire() {
                ticks.add(sim.now());
                if (ticks.size() < 5) {
                    sim.schedule(MILLIS, this);
                }
            }
        });
        sim.run();

        assertThat(ticks).containsExactly(MILLIS, 2 * MILLIS, 3 * MILLIS, 4 * MILLIS, 5 * MILLIS);
    }

    @Test
    @DisplayName("leaves later events pending when stopped at a deadline")
    void runUntilStopsAtTheDeadline() {
        final Simulation sim = new Simulation(1L);
        final List<String> fired = new ArrayList<>();

        sim.schedule(5 * MILLIS, () -> fired.add("inside"));
        sim.schedule(50 * MILLIS, () -> fired.add("outside"));
        sim.runUntil(10 * MILLIS);

        assertThat(fired).containsExactly("inside");
        assertThat(sim.pendingEvents()).isEqualTo(1);
    }

    @Test
    @DisplayName("advances the clock through a quiet period to the deadline")
    void quietPeriodsStillAdvanceTime() {
        final Simulation sim = new Simulation(1L);
        sim.schedule(MILLIS, () -> {});
        sim.runUntil(100 * MILLIS);

        // Without this the clock would sit at 1ms and a later scheduled delay would be
        // measured from the wrong instant.
        assertThat(sim.now()).isEqualTo(100 * MILLIS);
    }

    @Test
    @DisplayName("resumes where it stopped across successive runUntil calls")
    void successiveRunsResume() {
        final Simulation sim = new Simulation(1L);
        final List<String> fired = new ArrayList<>();

        sim.schedule(5 * MILLIS, () -> fired.add("a"));
        sim.schedule(50 * MILLIS, () -> fired.add("b"));

        sim.runUntil(10 * MILLIS);
        assertThat(fired).containsExactly("a");

        sim.runUntil(100 * MILLIS);
        assertThat(fired).containsExactly("a", "b");
    }

    @Test
    @DisplayName("a crash discards the crashed node's pending timers only")
    void crashCancelsOnlyTheCrashedNodesEvents() {
        final Simulation sim = new Simulation(1L);
        final List<String> fired = new ArrayList<>();

        sim.schedule(20 * MILLIS, 1, () -> fired.add("node1"));
        sim.schedule(20 * MILLIS, 2, () -> fired.add("node2"));
        sim.schedule(20 * MILLIS, Simulation.NO_OWNER, () -> fired.add("unowned"));

        sim.schedule(10 * MILLIS, () -> sim.crash(1));
        sim.run();

        // A real process losing its timers is the point: node 1's callback must not run
        // against state that no longer exists.
        assertThat(fired).containsExactly("node2", "unowned");
    }

    @Test
    @DisplayName("a node can schedule again after crashing")
    void restartAfterCrashWorks() {
        final Simulation sim = new Simulation(1L);
        final List<String> fired = new ArrayList<>();

        sim.schedule(30 * MILLIS, 1, () -> fired.add("stale"));
        sim.schedule(10 * MILLIS, () -> {
            sim.crash(1);
            sim.schedule(30 * MILLIS, 1, () -> fired.add("restarted"));
        });
        sim.run();

        assertThat(fired).containsExactly("restarted");
    }

    @Test
    @DisplayName("aborts rather than hangs when an event re-arms with no delay")
    void runawaySchedulerIsCaught() {
        final Simulation sim = new Simulation(1L, Trace.disabled(), 1_000L);
        sim.schedule(0L, new Simulation.Event() {
            @Override
            public void fire() {
                sim.schedule(0L, this);
            }
        });

        assertThatThrownBy(sim::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("step budget");
    }

    @Test
    @DisplayName("rejects scheduling into the past")
    void rejectsNegativeDelay() {
        final Simulation sim = new Simulation(1L);
        assertThatThrownBy(() -> sim.schedule(-1L, () -> {}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("delayNanos");
    }

    @Test
    @DisplayName("counts the events it fired")
    void countsFiredEvents() {
        final Simulation sim = new Simulation(1L);
        for (int i = 0; i < 10; i++) {
            sim.schedule((i + 1) * MILLIS, () -> {});
        }
        sim.run();
        assertThat(sim.eventsFired()).isEqualTo(10);
    }

    @Test
    @DisplayName("step fires one live event at a time in schedule order")
    void stepFiresOneEventAtATime() {
        final Simulation sim = new Simulation(1L);
        final List<String> fired = new ArrayList<>();

        sim.schedule(30 * MILLIS, () -> fired.add("third"));
        sim.schedule(10 * MILLIS, () -> fired.add("first"));
        sim.schedule(20 * MILLIS, () -> fired.add("second"));

        assertThat(sim.step()).isTrue();
        assertThat(fired).containsExactly("first");
        assertThat(sim.now()).isEqualTo(10 * MILLIS);

        assertThat(sim.step()).isTrue();
        assertThat(fired).containsExactly("first", "second");

        assertThat(sim.step(10)).isEqualTo(1);
        assertThat(fired).containsExactly("first", "second", "third");
        assertThat(sim.step()).isFalse();
        assertThat(sim.peek()).isEmpty();
    }

    @Test
    @DisplayName("peek reports the next live event without firing it")
    void peekDoesNotFire() {
        final Simulation sim = new Simulation(1L);
        final List<String> fired = new ArrayList<>();

        sim.schedule(5 * MILLIS, 3, () -> fired.add("a"));
        final Simulation.PendingEvent pending = sim.peek().orElseThrow();

        assertThat(pending.timeNanos()).isEqualTo(5 * MILLIS);
        assertThat(pending.owner()).isEqualTo(3);
        assertThat(fired).isEmpty();
        assertThat(sim.eventsFired()).isZero();
        assertThat(sim.pendingEvents()).isEqualTo(1);
    }

    @Test
    @DisplayName("step and peek skip events cancelled by a crash")
    void stepAndPeekSkipCancelledEvents() {
        final Simulation sim = new Simulation(1L);
        final List<String> fired = new ArrayList<>();

        sim.schedule(20 * MILLIS, 1, () -> fired.add("stale"));
        sim.schedule(30 * MILLIS, 2, () -> fired.add("alive"));
        sim.crash(1);

        assertThat(sim.peek().orElseThrow().owner()).isEqualTo(2);
        assertThat(sim.step()).isTrue();
        assertThat(fired).containsExactly("alive");
        assertThat(sim.step()).isFalse();
    }

    @Test
    @DisplayName("rejects a negative step count")
    void rejectsNegativeStepCount() {
        final Simulation sim = new Simulation(1L);
        assertThatThrownBy(() -> sim.step(-1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("count");
    }
}
