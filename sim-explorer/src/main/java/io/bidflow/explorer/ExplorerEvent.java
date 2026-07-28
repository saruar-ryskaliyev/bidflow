package io.bidflow.explorer;

/**
 * Structured journal entry for the explorer UI.
 *
 * <p>Network observations use the {@link io.bidflow.sim.NetworkEvent} kind names; control-plane
 * actions use {@code CONTROL} with a human-readable {@code detail}.
 *
 * @param seq monotonic sequence assigned by {@link ExplorerEventJournal}
 * @param timeNanos simulated instant when the observation was made
 * @param kind event kind, such as {@code SEND} or {@code CONTROL}
 * @param from source node, or {@code -1} when not directed
 * @param to destination node, or {@code -1} when not directed
 * @param label identifying message label, or empty for topology changes
 * @param duplicate whether this delivery is the duplicated copy of a send
 * @param detail extra detail for control events, otherwise empty
 */
public record ExplorerEvent(
        long seq,
        long timeNanos,
        String kind,
        int from,
        int to,
        String label,
        boolean duplicate,
        String detail) {

    public ExplorerEvent {
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        if (label == null) {
            throw new IllegalArgumentException("label must not be null");
        }
        if (detail == null) {
            throw new IllegalArgumentException("detail must not be null");
        }
    }
}
