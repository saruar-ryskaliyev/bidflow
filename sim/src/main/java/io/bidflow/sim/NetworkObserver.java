package io.bidflow.sim;

/**
 * Receives structured {@link NetworkEvent}s from a {@link SimNetwork}.
 *
 * <p>Observers must not draw from {@link Simulation#random()} or otherwise perturb the
 * schedule: observation that changes behaviour would make a traced explorer session diverge
 * from an unobserved one.
 */
@FunctionalInterface
public interface NetworkObserver {

    /** Called immediately when the network records an observation. */
    void onEvent(NetworkEvent event);

    /** An observer that does nothing. */
    static NetworkObserver noop() {
        return event -> {};
    }
}
