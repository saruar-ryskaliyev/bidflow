package io.bidflow.explorer;

import io.bidflow.budget.sim.BudgetCluster;
import io.bidflow.budget.sim.BudgetClusterConfig;
import io.bidflow.budget.sim.ClusterSnapshot;
import io.bidflow.budget.sim.SearchResult;
import io.bidflow.sim.NetworkConditions;
import io.bidflow.sim.NetworkEvent;
import io.bidflow.sim.NetworkObserver;
import io.bidflow.sim.Simulation;
import io.bidflow.sim.Trace;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Sole owner of a {@link Simulation} and {@link BudgetCluster} for one explorer session.
 *
 * <p>Every public method is synchronized so HTTP handlers can share one session safely.
 */
public final class ExplorerSession {

    public static final int MIN_SHARD_COUNT = 1;
    public static final int MAX_SHARD_COUNT = 32;

    public static final long MAX_STEPS_PER_CALL = 10_000L;
    public static final long MAX_ADVANCE_NANOS = 100_000_000L;

    private final ExplorerEventJournal eventJournal;
    private final ExplorerCommand.Reset bootstrap;

    private Simulation sim;
    private BudgetCluster cluster;
    private final List<ExplorerCommand> commandLog = new ArrayList<>();

    private long seed;
    private int shardCount;
    private long budgetMicros;
    private String networkPreset;
    private long[] clockOffsets;
    private boolean autoTraffic;
    private Long reclaimMarginNanos;

    public ExplorerSession(
            long seed,
            int shardCount,
            long budgetMicros,
            String networkPreset,
            Long uniformSkewNanos,
            long[] perShardSkewNanos,
            boolean autoTraffic,
            Long reclaimMarginNanos) {
        this(seed, shardCount, budgetMicros, networkPreset, uniformSkewNanos, perShardSkewNanos,
                autoTraffic, reclaimMarginNanos, ExplorerEventJournal.DEFAULT_CAPACITY);
    }

    ExplorerSession(
            long seed,
            int shardCount,
            long budgetMicros,
            String networkPreset,
            Long uniformSkewNanos,
            long[] perShardSkewNanos,
            boolean autoTraffic,
            Long reclaimMarginNanos,
            int eventJournalCapacity) {
        eventJournal = new ExplorerEventJournal(eventJournalCapacity);
        bootstrap = new ExplorerCommand.Reset(
                seed,
                shardCount,
                budgetMicros,
                networkPreset,
                uniformSkewNanos,
                perShardSkewNanos,
                autoTraffic,
                reclaimMarginNanos);
        reset(seed, shardCount, budgetMicros, networkPreset, uniformSkewNanos, perShardSkewNanos,
                autoTraffic, reclaimMarginNanos);
    }

    /** Applies a command after validation, recording it in the command journal on success. */
    public synchronized void apply(ExplorerCommand command) {
        if (command instanceof ExplorerCommand.Replay) {
            replaceFrom(replayed());
            commandLog.add(command);
            return;
        }
        execute(command);
        commandLog.add(command);
    }

    /** Fires the next pending simulation event. */
    public synchronized boolean step() {
        return sim.step();
    }

    /** Fires up to {@code count} pending simulation events. */
    public synchronized long step(long count) {
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative, was " + count);
        }
        final long clamped = Math.min(count, MAX_STEPS_PER_CALL);
        return sim.step(clamped);
    }

    /** Advances simulated time by up to {@code nanos}, firing events along the way. */
    public synchronized void advance(long nanos) {
        if (nanos < 0) {
            throw new IllegalArgumentException("nanos must not be negative, was " + nanos);
        }
        final long clamped = Math.min(nanos, MAX_ADVANCE_NANOS);
        sim.runUntil(sim.now() + clamped);
    }

    /** Returns the current cluster snapshot plus session metadata. */
    public synchronized ExplorerState snapshot() {
        final ClusterSnapshot clusterSnapshot = cluster.snapshot();
        return new ExplorerState(
                clusterSnapshot,
                seed,
                autoTraffic,
                networkPreset,
                eventJournal.nextSeq(),
                clusterSnapshot.eventsFired(),
                clusterSnapshot.nowNanos(),
                sim.peek(),
                commandLog.size());
    }

    /**
     * Rebuilds a fresh session from the stored seed, configuration, and command journal.
     *
     * <p>The {@link ExplorerCommand.Replay} command itself is excluded from replay.
     */
    public synchronized ExplorerSession replayed() {
        final ExplorerSession copy = new ExplorerSession(
                bootstrap.seed(),
                bootstrap.shardCount(),
                bootstrap.budgetMicros(),
                bootstrap.networkPreset(),
                bootstrap.uniformSkewNanos(),
                bootstrap.perShardSkewNanos(),
                bootstrap.autoTraffic(),
                bootstrap.reclaimMarginNanos(),
                eventJournalCapacity());
        for (ExplorerCommand command : commandLog) {
            if (command instanceof ExplorerCommand.Replay) {
                continue;
            }
            copy.execute(command);
            copy.commandLog.add(command);
        }
        return copy;
    }

    public synchronized String traceDigest() {
        return sim.trace().digest();
    }

    public synchronized List<ExplorerCommand> commands() {
        return List.copyOf(commandLog);
    }

    public synchronized long seed() {
        return seed;
    }

    public synchronized int shardCount() {
        return shardCount;
    }

    public synchronized long budgetMicros() {
        return budgetMicros;
    }

    public synchronized String networkPreset() {
        return networkPreset;
    }

    public synchronized boolean autoTraffic() {
        return autoTraffic;
    }

    public synchronized ExplorerEventPage eventsAfter(long afterSeq, int limit) {
        return eventJournal.eventsAfter(afterSeq, limit);
    }

    /** Maps shard {@code s} to simulator node {@code s + 1}. */
    public static int shardToNode(int shard) {
        return BudgetCluster.nodeOf(shard);
    }

    /** Maps simulator node {@code s + 1} back to shard {@code s}. */
    public static int nodeToShard(int node) {
        if (node <= BudgetCluster.AUTHORITY_NODE) {
            throw new IllegalArgumentException("node " + node + " is not a shard node");
        }
        return node - 1;
    }

    private void execute(ExplorerCommand command) {
        switch (command) {
            case ExplorerCommand.Reset reset -> reset(
                    reset.seed(),
                    reset.shardCount(),
                    reset.budgetMicros(),
                    reset.networkPreset(),
                    reset.uniformSkewNanos(),
                    reset.perShardSkewNanos(),
                    reset.autoTraffic(),
                    reset.reclaimMarginNanos());
            case ExplorerCommand.Step ignored -> step();
            case ExplorerCommand.StepN stepN -> step(stepN.count());
            case ExplorerCommand.Advance advance -> advance(advance.nanos());
            case ExplorerCommand.SetTraffic setTraffic -> setTraffic(setTraffic.enabled());
            case ExplorerCommand.Search search -> injectSearch(search.shard());
            case ExplorerCommand.CrashShard crash -> crashShard(crash.shard());
            case ExplorerCommand.RestartShard restart -> restartShard(restart.shard());
            case ExplorerCommand.Partition partition -> partition(partition.a(), partition.b());
            case ExplorerCommand.Heal heal -> heal(heal.a(), heal.b());
            case ExplorerCommand.HealAll ignored -> healAll();
            case ExplorerCommand.Replay ignored -> throw new IllegalStateException(
                    "replay is handled by apply(Replay)");
        }
    }

    private void reset(
            long newSeed,
            int newShardCount,
            long newBudgetMicros,
            String newNetworkPreset,
            Long uniformSkewNanos,
            long[] perShardSkewNanos,
            boolean newAutoTraffic,
            Long newReclaimMarginNanos) {
        validateShardCount(newShardCount);
        if (newBudgetMicros <= 0) {
            throw new IllegalArgumentException("budgetMicros must be positive, was " + newBudgetMicros);
        }
        final NetworkConditions conditions = networkConditionsFor(newNetworkPreset);
        final long[] offsets = resolveClockOffsets(newShardCount, uniformSkewNanos, perShardSkewNanos);

        this.seed = newSeed;
        this.shardCount = newShardCount;
        this.budgetMicros = newBudgetMicros;
        this.networkPreset = newNetworkPreset;
        this.clockOffsets = offsets;
        this.autoTraffic = newAutoTraffic;
        this.reclaimMarginNanos = newReclaimMarginNanos;

        this.sim = new Simulation(newSeed, Trace.enabled());
        this.cluster = buildCluster(conditions, offsets);
        this.cluster.start();
        this.cluster.setTrafficEnabled(newAutoTraffic);

        eventJournal.clear();
        emitControl("reset seed=" + newSeed + " shards=" + newShardCount, -1, -1);
    }

    private BudgetCluster buildCluster(NetworkConditions conditions, long[] offsets) {
        BudgetClusterConfig config = BudgetClusterConfig.defaults()
                .shardCount(shardCount)
                .budgetMicros(budgetMicros);
        if (reclaimMarginNanos != null) {
            config.reclaimMarginNanos(reclaimMarginNanos);
        }
        NetworkObserver observer = this::onNetworkEvent;
        return new BudgetCluster(sim, config, conditions, offsets, observer);
    }

    private void setTraffic(boolean enabled) {
        autoTraffic = enabled;
        cluster.setTrafficEnabled(enabled);
        emitControl("traffic " + (enabled ? "on" : "off"), -1, -1);
    }

    private SearchResult injectSearch(int shard) {
        validateShard(shard);
        final SearchResult result = cluster.injectSearch(shard);
        emitControl(
                "search shard=" + shard
                        + " won=" + result.wonAuction()
                        + " served=" + result.served()
                        + " cost=" + result.costMicros(),
                shardToNode(shard),
                BudgetCluster.AUTHORITY_NODE);
        return result;
    }

    private void crashShard(int shard) {
        validateShard(shard);
        cluster.crashShard(shard);
        emitControl("crash shard=" + shard, shardToNode(shard), -1);
    }

    private void restartShard(int shard) {
        validateShard(shard);
        cluster.restartShard(shard);
        emitControl(
                "restart shard=" + shard + " incarnation=" + cluster.wallet(shard).incarnation(),
                shardToNode(shard),
                -1);
    }

    private void partition(int a, int b) {
        validateNode(a);
        validateNode(b);
        cluster.network().partition(a, b);
        emitControl("partition " + a + "<->" + b, a, b);
    }

    private void heal(int a, int b) {
        validateNode(a);
        validateNode(b);
        cluster.network().heal(a, b);
        emitControl("heal " + a + "<->" + b, a, b);
    }

    private void healAll() {
        cluster.network().healAll();
        emitControl("heal all", -1, -1);
    }

    private void onNetworkEvent(NetworkEvent event) {
        eventJournal.append(new ExplorerEvent(
                0L,
                event.timeNanos(),
                event.kind().name(),
                event.from(),
                event.to(),
                event.label(),
                event.duplicate(),
                ""));
    }

    private void emitControl(String detail, int from, int to) {
        eventJournal.append(new ExplorerEvent(
                0L, sim.now(), "CONTROL", from, to, "", false, detail));
    }

    private void replaceFrom(ExplorerSession other) {
        this.sim = other.sim;
        this.cluster = other.cluster;
        this.commandLog.clear();
        this.commandLog.addAll(other.commandLog);
        this.seed = other.seed;
        this.shardCount = other.shardCount;
        this.budgetMicros = other.budgetMicros;
        this.networkPreset = other.networkPreset;
        this.clockOffsets = other.clockOffsets;
        this.autoTraffic = other.autoTraffic;
        this.reclaimMarginNanos = other.reclaimMarginNanos;
        replaceEventJournal(other.eventJournal);
    }

    private void replaceEventJournal(ExplorerEventJournal other) {
        eventJournal.copyFrom(other);
    }

    private int eventJournalCapacity() {
        return eventJournal.capacity();
    }

    private void validateShard(int shard) {
        if (shard < 0 || shard >= shardCount) {
            throw new IllegalArgumentException(
                    "shard must be in [0, " + shardCount + "), was " + shard);
        }
    }

    private void validateNode(int node) {
        final int nodeCount = shardCount + 1;
        if (node < 0 || node >= nodeCount) {
            throw new IllegalArgumentException(
                    "node must be in [0, " + nodeCount + "), was " + node);
        }
    }

    private static void validateShardCount(int value) {
        if (value < MIN_SHARD_COUNT || value > MAX_SHARD_COUNT) {
            throw new IllegalArgumentException(
                    "shardCount must be in [" + MIN_SHARD_COUNT + ", " + MAX_SHARD_COUNT + "], was " + value);
        }
    }

    private static NetworkConditions networkConditionsFor(String preset) {
        if (preset == null) {
            throw new IllegalArgumentException("networkPreset must not be null");
        }
        return switch (preset.toLowerCase()) {
            case "perfect" -> NetworkConditions.perfect();
            case "lan" -> NetworkConditions.lan();
            case "wan" -> NetworkConditions.wan();
            case "hostile" -> NetworkConditions.hostile();
            default -> throw new IllegalArgumentException("unknown network preset: " + preset);
        };
    }

    private static long[] resolveClockOffsets(
            int shards, Long uniformSkewNanos, long[] perShardSkewNanos) {
        if (perShardSkewNanos != null) {
            if (perShardSkewNanos.length != shards) {
                throw new IllegalArgumentException(
                        "expected " + shards + " per-shard skew values, got " + perShardSkewNanos.length);
            }
            return perShardSkewNanos.clone();
        }
        final long skew = uniformSkewNanos == null ? 0L : uniformSkewNanos;
        final long[] offsets = new long[shards];
        for (int shard = 0; shard < shards; shard++) {
            offsets[shard] = skew;
        }
        return offsets;
    }
}
