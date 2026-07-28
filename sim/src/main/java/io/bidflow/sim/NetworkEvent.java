package io.bidflow.sim;

/**
 * A typed observation of something the simulated network did.
 *
 * <p>Text {@link Trace} entries remain the source of truth for digest-based determinism
 * checks. This record is the structured feed an interactive explorer polls without parsing
 * those strings.
 *
 * @param timeNanos simulated instant when the observation was made
 * @param kind what happened
 * @param from source node, or {@code -1} when the observation is not directed
 * @param to destination node, or {@code -1} when the observation is not directed
 * @param label identifying message label, or empty for topology changes
 * @param duplicate whether this delivery is the duplicated copy of a send
 */
public record NetworkEvent(
        long timeNanos, Kind kind, int from, int to, String label, boolean duplicate) {

    public enum Kind {
        SEND,
        DELIVER,
        DROP,
        BLOCK,
        DUPLICATE,
        PARTITION,
        HEAL
    }

    public NetworkEvent {
        if (label == null) {
            throw new IllegalArgumentException("label must not be null");
        }
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
    }

    public static NetworkEvent send(long timeNanos, int from, int to, String label) {
        return new NetworkEvent(timeNanos, Kind.SEND, from, to, label, false);
    }

    public static NetworkEvent deliver(
            long timeNanos, int from, int to, String label, boolean duplicate) {
        return new NetworkEvent(timeNanos, Kind.DELIVER, from, to, label, duplicate);
    }

    public static NetworkEvent drop(long timeNanos, int from, int to, String label) {
        return new NetworkEvent(timeNanos, Kind.DROP, from, to, label, false);
    }

    public static NetworkEvent block(long timeNanos, int from, int to, String label) {
        return new NetworkEvent(timeNanos, Kind.BLOCK, from, to, label, false);
    }

    public static NetworkEvent duplicate(long timeNanos, int from, int to, String label) {
        return new NetworkEvent(timeNanos, Kind.DUPLICATE, from, to, label, true);
    }

    public static NetworkEvent partition(long timeNanos, int from, int to) {
        return new NetworkEvent(timeNanos, Kind.PARTITION, from, to, "", false);
    }

    public static NetworkEvent heal(long timeNanos, int from, int to) {
        return new NetworkEvent(timeNanos, Kind.HEAL, from, to, "", false);
    }
}
