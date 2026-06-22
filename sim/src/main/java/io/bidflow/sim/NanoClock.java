package io.bidflow.sim;

/**
 * A source of nanosecond timestamps.
 *
 * <p>Named to avoid confusion with {@link java.time.Clock}, and deliberately narrower:
 * distributed budget enforcement only ever needs elapsed nanoseconds, never a wall date.
 *
 * <p>Every component that reads time takes one of these rather than calling
 * {@link System#nanoTime()} directly. That is the whole basis of the simulation: if any
 * component reaches for the real clock, its behaviour stops being reproducible and no
 * amount of seeding will recover it.
 */
public interface NanoClock {

    /** Nanoseconds elapsed on this clock's own timeline. */
    long nanos();
}
