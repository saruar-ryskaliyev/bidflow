package io.bidflow.sim;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An ordered log of what happened during a simulation run.
 *
 * <p>The trace is how determinism is checked. Two runs of the same scenario under the same
 * seed must produce byte-identical traces; if they do not, something in the system under
 * test is reading the wall clock, iterating a hash-ordered collection, or depending on
 * thread scheduling, and every later result is untrustworthy.
 *
 * <p>Comparing whole traces is how a failure gets diagnosed, but comparing {@link #digest()}
 * is how equality gets asserted — one short hex string instead of a hundred thousand lines.
 *
 * <p>Tracing costs a string per event, which is far too expensive for a serving path but
 * irrelevant inside a simulation. It can be switched off with {@link #disabled()} for long
 * runs where only the final invariants matter.
 */
public final class Trace {

    /** Default cap on retained entries, so a multi-day simulation cannot exhaust the heap. */
    public static final int DEFAULT_LIMIT = 2_000_000;

    public record Entry(long timeNanos, String message) {
        @Override
        public String toString() {
            return timeNanos + " " + message;
        }
    }

    private final List<Entry> entries;
    private final boolean enabled;
    private final int limit;
    private long recorded;
    private long truncated;

    private Trace(boolean enabled, int limit) {
        this.enabled = enabled;
        this.limit = limit;
        this.entries = enabled ? new ArrayList<>() : List.of();
    }

    public static Trace enabled() {
        return new Trace(true, DEFAULT_LIMIT);
    }

    public static Trace enabled(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive, was " + limit);
        }
        return new Trace(true, limit);
    }

    /** A trace that records nothing, for runs long enough that the log would dominate. */
    public static Trace disabled() {
        return new Trace(false, DEFAULT_LIMIT);
    }

    public void record(long timeNanos, String message) {
        if (!enabled) {
            return;
        }
        recorded++;
        if (entries.size() == limit) {
            truncated++;
            return;
        }
        entries.add(new Entry(timeNanos, message));
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Entries in the order they occurred. */
    public List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    /** Number of retained entries (after any truncation at the limit). */
    public int size() {
        return entries.size();
    }

    /** Retained entry at {@code index}, in the order recorded. */
    public Entry entryAt(int index) {
        return entries.get(index);
    }

    /**
     * Retained entries from {@code index} inclusive to the end.
     *
     * @throws IndexOutOfBoundsException if {@code index} is outside {@code [0, size()]}
     */
    public List<Entry> entriesFrom(int index) {
        if (index < 0 || index > entries.size()) {
            throw new IndexOutOfBoundsException(
                    "index " + index + " is outside [0, " + entries.size() + "]");
        }
        return Collections.unmodifiableList(entries.subList(index, entries.size()));
    }

    /** Total events offered, including any dropped once {@link #DEFAULT_LIMIT} was hit. */
    public long recordedCount() {
        return recorded;
    }

    public long truncatedCount() {
        return truncated;
    }

    /**
     * SHA-256 over the whole trace, as lowercase hex.
     *
     * <p>A cryptographic hash rather than a cheap checksum: two runs that differ anywhere
     * must differ here, and an accidental collision would produce a passing determinism
     * test, which is the one failure this class exists to prevent.
     */
    public String digest() {
        final MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 is required of every JVM", e);
        }
        for (Entry entry : entries) {
            sha256.update(Long.toString(entry.timeNanos()).getBytes(StandardCharsets.UTF_8));
            sha256.update((byte) ' ');
            sha256.update(entry.message().getBytes(StandardCharsets.UTF_8));
            sha256.update((byte) '\n');
        }
        return hex(sha256.digest());
    }

    private static String hex(byte[] bytes) {
        final StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16));
            out.append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }
}
