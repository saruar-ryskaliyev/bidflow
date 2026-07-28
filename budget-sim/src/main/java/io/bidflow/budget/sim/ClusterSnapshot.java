package io.bidflow.budget.sim;

import java.util.List;

/**
 * Immutable view of a {@link BudgetCluster} for UI sessions and assertions.
 *
 * <p>Money fields are labelled explicitly so leased-but-unspent authority is not confused
 * with headroom or with harness ground truth.
 *
 * @param budgetMicros campaign budget cap
 * @param settledMicros spend accepted as final on the authority
 * @param outstandingMicros face value of live leases still outstanding
 * @param headroomMicros budget not yet leased out
 * @param observedSpendMicros settled plus latest reported spend on live leases
 * @param actualSpendMicros harness ground truth for committed spend
 * @param overspendMicros {@code max(0, actualSpendMicros - budgetMicros)}
 * @param spendableRemainderMicros {@code max(0, budgetMicros - actualSpendMicros)}
 * @param shards per-shard wallet state
 * @param networkSent messages sent
 * @param networkDelivered messages delivered
 * @param networkDropped messages dropped
 * @param networkDuplicated duplicate deliveries scheduled
 * @param networkPartitioned messages blocked by partition
 * @param servedRequests requests the harness committed spend for
 * @param refusedRequests won auctions the wallet refused
 * @param lostAuctions requests where our campaign was outranked
 * @param restarts shard restarts observed by the harness
 * @param blockedLinks directed edges currently partitioned
 * @param nowNanos simulated time
 * @param eventsFired total events executed on the simulation
 */
public record ClusterSnapshot(
        long budgetMicros,
        long settledMicros,
        long outstandingMicros,
        long headroomMicros,
        long observedSpendMicros,
        long actualSpendMicros,
        long overspendMicros,
        long spendableRemainderMicros,
        List<ShardSnapshot> shards,
        long networkSent,
        long networkDelivered,
        long networkDropped,
        long networkDuplicated,
        long networkPartitioned,
        long servedRequests,
        long refusedRequests,
        long lostAuctions,
        long restarts,
        List<BlockedLink> blockedLinks,
        long nowNanos,
        long eventsFired) {

    /** A directed network edge that is currently blocked. */
    public record BlockedLink(int from, int to) {}
}
