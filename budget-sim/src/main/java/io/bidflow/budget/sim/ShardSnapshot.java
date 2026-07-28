package io.bidflow.budget.sim;

/**
 * One shard's wallet and liveness at a snapshot instant.
 *
 * @param id shard index
 * @param alive whether traffic is scheduled for this shard
 * @param incarnation current wallet generation
 * @param remainingMicros unspent authority on the live lease
 * @param leaseId live lease id, or {@link io.bidflow.budget.Lease#NONE}
 * @param leaseSpentMicros spend against the live lease
 * @param lifetimeSpentMicros spend across every lease this incarnation held
 * @param expiresAtNanos authority deadline on the live lease
 * @param clockOffsetNanos skew relative to the authority clock
 * @param pendingReleaseId displaced lease awaiting settlement, or {@link io.bidflow.budget.Lease#NONE}
 */
public record ShardSnapshot(
        int id,
        boolean alive,
        long incarnation,
        long remainingMicros,
        long leaseId,
        long leaseSpentMicros,
        long lifetimeSpentMicros,
        long expiresAtNanos,
        long clockOffsetNanos,
        long pendingReleaseId) {}
