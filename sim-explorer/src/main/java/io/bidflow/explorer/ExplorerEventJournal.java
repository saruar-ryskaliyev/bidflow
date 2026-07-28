package io.bidflow.explorer;

import java.util.ArrayList;
import java.util.List;

/**
 * Bounded ring buffer of {@link ExplorerEvent}s for interactive polling.
 *
 * <p>When full, the oldest entries are discarded. Clients that poll with an {@code afterSeq}
 * below {@link #earliestSeq()} receive {@link ExplorerEventPage#truncated()} {@code true}.
 */
public final class ExplorerEventJournal {

    public static final int DEFAULT_CAPACITY = 10_000;

    private final ExplorerEvent[] ring;
    private final int capacity;

    private int size;
    private int head;
    private long startSeq = 1L;
    private long nextSeq = 1L;

    public ExplorerEventJournal() {
        this(DEFAULT_CAPACITY);
    }

    public ExplorerEventJournal(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive, was " + capacity);
        }
        this.capacity = capacity;
        this.ring = new ExplorerEvent[capacity];
    }

    /**
     * Appends an event, assigning the next monotonic sequence number.
     *
     * @param event template event; its {@code seq} field is ignored
     * @return stored event with an assigned {@code seq}
     */
    public synchronized ExplorerEvent append(ExplorerEvent event) {
        final long seq = nextSeq++;
        final ExplorerEvent stored = new ExplorerEvent(
                seq,
                event.timeNanos(),
                event.kind(),
                event.from(),
                event.to(),
                event.label(),
                event.duplicate(),
                event.detail());
        if (size < capacity) {
            ring[(head + size) % capacity] = stored;
            size++;
        } else {
            ring[head] = stored;
            head = (head + 1) % capacity;
            startSeq++;
        }
        return stored;
    }

    /**
     * Returns events with {@code seq > afterSeq}, up to {@code limit}.
     *
     * @param afterSeq client cursor; {@code 0} returns from the start of retention
     * @param limit maximum number of events to return
     */
    public synchronized ExplorerEventPage eventsAfter(long afterSeq, int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must not be negative, was " + limit);
        }
        if (afterSeq < 0) {
            throw new IllegalArgumentException("afterSeq must not be negative, was " + afterSeq);
        }

        final boolean truncated = afterSeq > 0 && afterSeq < startSeq;
        final List<ExplorerEvent> events = new ArrayList<>(Math.min(limit, size));
        for (int i = 0; i < size && events.size() < limit; i++) {
            final ExplorerEvent event = ring[(head + i) % capacity];
            if (event.seq() > afterSeq) {
                events.add(event);
            }
        }

        final long pageNextSeq = events.isEmpty() ? nextSeq : events.getLast().seq() + 1;
        return new ExplorerEventPage(events, pageNextSeq, truncated);
    }

    /** Number of events currently retained in the ring. */
    public synchronized int size() {
        return size;
    }

    /** Lowest sequence number still retained, or {@code nextSeq} when empty. */
    public synchronized long earliestSeq() {
        return size == 0 ? nextSeq : startSeq;
    }

    /** Sequence number that the next append would assign. */
    public synchronized long nextSeq() {
        return nextSeq;
    }

    /** Ring capacity configured at construction. */
    public int capacity() {
        return capacity;
    }

    /** Clears retained events without changing capacity; the next append assigns seq {@code 1}. */
    public synchronized void clear() {
        size = 0;
        head = 0;
        startSeq = 1L;
        nextSeq = 1L;
    }

    /** Replaces retained events with another journal's contents. */
    public synchronized void copyFrom(ExplorerEventJournal other) {
        synchronized (other) {
            clear();
            for (int i = 0; i < other.size; i++) {
                ring[i] = other.ring[(other.head + i) % other.capacity];
            }
            size = other.size;
            head = 0;
            startSeq = other.startSeq;
            nextSeq = other.nextSeq;
        }
    }
}
