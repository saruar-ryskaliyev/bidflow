package io.bidflow.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PacingControllerTest {

    private static final long DAY = 1_000_000_000L;
    private static final long BUDGET = 1_000_000L;
    private static final int GAIN = 20_000; // 200% of excess/budget
    private static final int MAX_THROTTLE = PacingController.BPS; // may cut to zero

    private static PacingController controller() {
        return new PacingController(0L, DAY, BUDGET, GAIN, MAX_THROTTLE);
    }

    @Nested
    @DisplayName("target curve")
    class TargetCurve {

        @Test
        @DisplayName("is zero at and before the day start")
        void zeroBeforeDay() {
            final PacingController pacing = controller();
            assertThat(pacing.targetSpendMicros(0L)).isZero();
            assertThat(pacing.targetSpendMicros(-1L)).isZero();
        }

        @Test
        @DisplayName("reaches the full budget at and after the day end")
        void fullAtDayEnd() {
            final PacingController pacing = controller();
            assertThat(pacing.targetSpendMicros(DAY)).isEqualTo(BUDGET);
            assertThat(pacing.targetSpendMicros(DAY + 1)).isEqualTo(BUDGET);
        }

        @Test
        @DisplayName("is linear halfway through the day")
        void linearMidpoint() {
            assertThat(controller().targetSpendMicros(DAY / 2)).isEqualTo(BUDGET / 2);
        }

        @Property(tries = 200)
        @DisplayName("target is monotonic non-decreasing in time")
        void targetIsMonotonic(
                @ForAll @LongRange(min = 0, max = DAY) long t1,
                @ForAll @LongRange(min = 0, max = DAY) long t2) {
            final PacingController pacing = controller();
            final long earlier = Math.min(t1, t2);
            final long later = Math.max(t1, t2);
            assertThat(pacing.targetSpendMicros(later))
                    .isGreaterThanOrEqualTo(pacing.targetSpendMicros(earlier));
        }
    }

    @Nested
    @DisplayName("grant capping")
    class GrantCapping {

        @Test
        @DisplayName("passes the request through when spend is behind the target")
        void fullGrantWhenBehind() {
            final PacingController pacing = controller();
            // Mid-day target is 500k; observed 100k → behind.
            assertThat(pacing.capWantedMicros(50_000L, 100_000L, DAY / 2)).isEqualTo(50_000L);
        }

        @Test
        @DisplayName("passes the request through when spend matches the target")
        void fullGrantWhenOnTrack() {
            assertThat(controller().capWantedMicros(50_000L, BUDGET / 2, DAY / 2)).isEqualTo(50_000L);
        }

        @Test
        @DisplayName("reduces the grant when spend is ahead of the target")
        void cutsWhenAhead() {
            final PacingController pacing = controller();
            // Mid-day target 500k; observed 750k → excess 250k.
            // throttleBps = 20000 * 250000 / 1000000 = 5000 → keep half.
            assertThat(pacing.capWantedMicros(100_000L, 750_000L, DAY / 2)).isEqualTo(50_000L);
        }

        @Test
        @DisplayName("can cut a grant to zero when far ahead")
        void canCutToZero() {
            final PacingController pacing = controller();
            // Early in the day, already spent the whole budget.
            assertThat(pacing.capWantedMicros(100_000L, BUDGET, DAY / 10)).isZero();
        }

        @Test
        @DisplayName("never returns more than requested")
        void neverExceedsRequest() {
            assertThat(controller().capWantedMicros(10L, 0L, DAY)).isEqualTo(10L);
        }

        @Test
        @DisplayName("rejects a negative request")
        void rejectsNegativeRequest() {
            assertThatThrownBy(() -> controller().capWantedMicros(-1L, 0L, 0L))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Property(tries = 300)
    @DisplayName("cap is always in [0, requested]")
    void capStaysInRange(
            @ForAll @LongRange(min = 0, max = 1_000_000L) long requested,
            @ForAll @LongRange(min = 0, max = 2_000_000L) long observed,
            @ForAll @LongRange(min = 0, max = DAY) long now,
            @ForAll @IntRange(min = 0, max = 50_000) int gain,
            @ForAll @IntRange(min = 0, max = PacingController.BPS) int maxThrottle) {
        final PacingController pacing = new PacingController(0L, DAY, BUDGET, gain, maxThrottle);
        final long capped = pacing.capWantedMicros(requested, observed, now);
        assertThat(capped).isBetween(0L, requested);
    }

    @Test
    @DisplayName("identical inputs always yield the same cap")
    void deterministic() {
        final PacingController pacing = controller();
        final List<Long> first = new ArrayList<>();
        final List<Long> second = new ArrayList<>();
        for (long t = 0; t <= DAY; t += DAY / 20) {
            first.add(pacing.capWantedMicros(80_000L, t / 2, t));
            second.add(pacing.capWantedMicros(80_000L, t / 2, t));
        }
        assertThat(second).isEqualTo(first);
    }
}
