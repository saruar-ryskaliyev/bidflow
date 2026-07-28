package io.bidflow.explorer;

import java.util.ArrayList;
import java.util.List;

/**
 * A page of journal events returned by {@link ExplorerEventJournal#eventsAfter(long, int)}.
 *
 * @param events events with {@code seq > afterSeq}, in ascending order
 * @param nextSeq sequence the client should pass as {@code afterSeq} on the next poll
 * @param truncated {@code true} when {@code afterSeq} fell off the retained ring
 */
public record ExplorerEventPage(List<ExplorerEvent> events, long nextSeq, boolean truncated) {

    public ExplorerEventPage {
        events = List.copyOf(events);
    }
}
