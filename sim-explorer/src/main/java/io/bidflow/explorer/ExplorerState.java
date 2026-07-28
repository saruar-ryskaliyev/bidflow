package io.bidflow.explorer;

import io.bidflow.budget.sim.ClusterSnapshot;
import io.bidflow.sim.Simulation.PendingEvent;
import java.util.Optional;

/**
 * Immutable view of an {@link ExplorerSession} for polling clients.
 *
 * @param snapshot cluster money and shard state
 * @param seed simulation seed
 * @param autoTraffic whether background traffic is enabled
 * @param networkPreset network preset name, such as {@code lan}
 * @param nextEventSeq next event sequence the client should poll from
 * @param eventsFired total simulation events executed so far
 * @param nowNanos current simulated time
 * @param pending next pending simulation event, if any
 * @param commandCount commands recorded in the session journal
 */
public record ExplorerState(
        ClusterSnapshot snapshot,
        long seed,
        boolean autoTraffic,
        String networkPreset,
        long nextEventSeq,
        long eventsFired,
        long nowNanos,
        Optional<PendingEvent> pending,
        int commandCount) {}
