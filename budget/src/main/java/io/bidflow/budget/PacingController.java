package io.bidflow.budget;

/**
 * A deterministic proportional pacing controller.
 *
 * <p>Compares observed spend to a target curve over a budget day and caps the next lease
 * grant when the campaign is ahead of schedule. All ratios use fixed-point basis points so
 * the controller is free of floating point and fully replayable.
 *
 * <p>The default target is linear: {@code budget * elapsed / dayDuration}. When observed
 * spend is at or behind the target the request passes through unchanged; when ahead, the
 * grant is reduced by {@code gainBps * excess / budget}, clamped to
 * {@code [0, maxThrottleBps]} of the request.
 */
public final class PacingController implements LeaseGrantPolicy {

    /** Basis-point scale: 10_000 == 100%. */
    public static final int BPS = 10_000;

    private final long dayStartNanos;
    private final long dayDurationNanos;
    private final long dailyBudgetMicros;
    private final int gainBps;
    private final int maxThrottleBps;

    /**
     * @param dayStartNanos authority-clock instant the budget day begins
     * @param dayDurationNanos length of the budget day; must be positive
     * @param dailyBudgetMicros the campaign's spend target for the day
     * @param gainBps how aggressively to cut when ahead (basis points of excess/budget)
     * @param maxThrottleBps maximum fraction of a request that may be cut, in basis points
     */
    public PacingController(
            long dayStartNanos,
            long dayDurationNanos,
            long dailyBudgetMicros,
            int gainBps,
            int maxThrottleBps) {
        if (dayDurationNanos <= 0) {
            throw new IllegalArgumentException(
                    "dayDurationNanos must be positive, was " + dayDurationNanos);
        }
        if (dailyBudgetMicros < 0) {
            throw new IllegalArgumentException(
                    "dailyBudgetMicros must not be negative, was " + dailyBudgetMicros);
        }
        if (gainBps < 0) {
            throw new IllegalArgumentException("gainBps must not be negative, was " + gainBps);
        }
        if (maxThrottleBps < 0 || maxThrottleBps > BPS) {
            throw new IllegalArgumentException(
                    "maxThrottleBps must be in [0, " + BPS + "], was " + maxThrottleBps);
        }
        this.dayStartNanos = dayStartNanos;
        this.dayDurationNanos = dayDurationNanos;
        this.dailyBudgetMicros = dailyBudgetMicros;
        this.gainBps = gainBps;
        this.maxThrottleBps = maxThrottleBps;
    }

    /** Linear target: zero before the day, full budget after it, proportional in between. */
    public long targetSpendMicros(long nowNanos) {
        if (nowNanos <= dayStartNanos || dailyBudgetMicros == 0) {
            return 0L;
        }
        final long elapsed = nowNanos - dayStartNanos;
        if (elapsed >= dayDurationNanos) {
            return dailyBudgetMicros;
        }
        // budget * elapsed / duration — multiply first; both factors are bounded by construction.
        return Math.multiplyExact(dailyBudgetMicros, elapsed) / dayDurationNanos;
    }

    @Override
    public long capWantedMicros(long requestedMicros, long observedSpendMicros, long nowNanos) {
        if (requestedMicros < 0) {
            throw new IllegalArgumentException(
                    "requestedMicros must not be negative, was " + requestedMicros);
        }
        if (observedSpendMicros < 0) {
            throw new IllegalArgumentException(
                    "observedSpendMicros must not be negative, was " + observedSpendMicros);
        }
        if (requestedMicros == 0) {
            return 0L;
        }
        final long target = targetSpendMicros(nowNanos);
        if (observedSpendMicros <= target) {
            return requestedMicros;
        }
        if (dailyBudgetMicros == 0) {
            return 0L;
        }

        final long excess = observedSpendMicros - target;
        // throttleBps = min(maxThrottle, gain * excess / budget), all in basis points.
        long throttleBps = Math.multiplyExact(gainBps, excess) / dailyBudgetMicros;
        if (throttleBps > maxThrottleBps) {
            throttleBps = maxThrottleBps;
        }
        if (throttleBps <= 0) {
            return requestedMicros;
        }
        if (throttleBps >= BPS) {
            return 0L;
        }
        // Keep (BPS - throttle) / BPS of the request.
        return Math.multiplyExact(requestedMicros, BPS - throttleBps) / BPS;
    }

    public long dayStartNanos() {
        return dayStartNanos;
    }

    public long dayDurationNanos() {
        return dayDurationNanos;
    }

    public long dailyBudgetMicros() {
        return dailyBudgetMicros;
    }

    public int gainBps() {
        return gainBps;
    }

    public int maxThrottleBps() {
        return maxThrottleBps;
    }
}
