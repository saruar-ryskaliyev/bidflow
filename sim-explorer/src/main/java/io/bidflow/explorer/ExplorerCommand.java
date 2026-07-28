package io.bidflow.explorer;

/**
 * Accepted explorer commands with typed payloads.
 *
 * <p>Node ids follow {@link io.bidflow.budget.sim.BudgetCluster}: authority is {@code 0},
 * shard {@code s} is {@code s + 1}.
 */
public sealed interface ExplorerCommand {

    /** Rebuilds the session from scratch with a new seed and configuration. */
    record Reset(
            long seed,
            int shardCount,
            long budgetMicros,
            String networkPreset,
            Long uniformSkewNanos,
            long[] perShardSkewNanos,
            boolean autoTraffic,
            Long reclaimMarginNanos)
            implements ExplorerCommand {}

    /** Fires the next pending simulation event. */
    record Step() implements ExplorerCommand {}

    /** Fires up to {@code count} pending simulation events. */
    record StepN(long count) implements ExplorerCommand {}

    /** Advances simulated time by up to {@code nanos}, firing events along the way. */
    record Advance(long nanos) implements ExplorerCommand {}

    /** Enables or disables background traffic on every shard. */
    record SetTraffic(boolean enabled) implements ExplorerCommand {}

    /** Runs one injected search on a shard without re-arming background traffic. */
    record Search(int shard) implements ExplorerCommand {}

    /** Kills a shard without restarting it. */
    record CrashShard(int shard) implements ExplorerCommand {}

    /** Restarts a shard with a fresh wallet. */
    record RestartShard(int shard) implements ExplorerCommand {}

    /** Blocks traffic in both directions between two nodes. */
    record Partition(int a, int b) implements ExplorerCommand {}

    /** Restores bidirectional traffic between two nodes. */
    record Heal(int a, int b) implements ExplorerCommand {}

    /** Clears every partition in the simulated network. */
    record HealAll() implements ExplorerCommand {}

    /** Rebuilds this session from the stored seed, config, and command journal. */
    record Replay() implements ExplorerCommand {}
}
