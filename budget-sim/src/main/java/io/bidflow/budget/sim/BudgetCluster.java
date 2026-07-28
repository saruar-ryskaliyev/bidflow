package io.bidflow.budget.sim;

import io.bidflow.auction.AuctionEngine;
import io.bidflow.auction.AuctionOutcome;
import io.bidflow.auction.AuctionRequest;
import io.bidflow.budget.BudgetAuthority;
import io.bidflow.budget.Lease;
import io.bidflow.budget.SpendAuthority;
import io.bidflow.sim.NetworkConditions;
import io.bidflow.sim.NetworkObserver;
import io.bidflow.sim.NodeClock;
import io.bidflow.sim.SimNetwork;
import io.bidflow.sim.Simulation;
import java.util.ArrayList;
import java.util.List;

/**
 * A whole budget-enforcement deployment inside the simulator: one authority, many serving
 * shards, traffic trying to spend money, and faults free to interrupt any of it.
 *
 * <p>Spend is priced by a real {@code auction-core} auction rather than a synthetic cost
 * draw: each request pits the budget-enforced campaign against seeded competitors, and
 * what the wallet is asked to reserve is the GSP price the engine actually cleared. Losing
 * the auction spends nothing, which is also true in production.
 *
 * <p>The authority is node 0 and shard {@code s} is node {@code s + 1}, so the simulator's crash
 * and partition controls address them directly.
 *
 * <p>The authority's clock is treated as the reference frame with zero error, and shards are
 * skewed relative to it. Skew is relative, so this loses no generality and gives the reclaim
 * margin a single unambiguous quantity to be compared against.
 *
 * <p>{@link #actualSpendMicros()} is the number that matters. The harness increments it itself on
 * every committed spend, never deriving it from anything the authority or the shards believe. If
 * the measurement came out of the components under test, a bug that corrupted the accounting
 * would also corrupt the measurement, and the test would pass while the money went missing.
 */
public final class BudgetCluster {

    public static final int AUTHORITY_NODE = 0;

    /** The campaign whose budget is enforced; competitors carry ids from 100 upward. */
    private static final long OUR_CAMPAIGN_ID = 1L;

    private static final long COMPETITOR_ID_BASE = 100L;

    private final Simulation sim;
    private final SimNetwork net;
    private final BudgetClusterConfig config;
    private final BudgetAuthority authority;
    private final NodeClock authorityClock;
    private final SpendAuthority[] wallets;
    private final NodeClock[] clocks;
    private final long[] clockOffsetsNanos;
    private final boolean[] alive;
    private final long[] incarnations;
    private final long[] nextRequestAllowedAt;
    private final long[] nextReportAt;
    private final AuctionEngine[] engines;
    private final AuctionRequest[] requests;
    private final AuctionOutcome[] outcomes;

    /** The cheapest price a won slot can carry; below this a wallet cannot spend at all. */
    private final long minPriceFloorMicros;

    private boolean trafficEnabled = true;

    private long actualSpendMicros;
    private long servedRequests;
    private long refusedRequests;
    private long lostAuctions;
    private long restarts;

    /** Cumulative spend samples taken on the authority clock; empty when checkpoints are off. */
    private final List<long[]> spendCheckpoints = new ArrayList<>();
    private long runHorizonNanos;

    public BudgetCluster(
            Simulation sim, BudgetClusterConfig config, NetworkConditions conditions) {
        this(sim, config, conditions, new long[config.shardCount()]);
    }

    public BudgetCluster(
            Simulation sim,
            BudgetClusterConfig config,
            NetworkConditions conditions,
            long[] clockOffsetsNanos) {
        this(sim, config, conditions, clockOffsetsNanos, NetworkObserver.noop());
    }

    public BudgetCluster(
            Simulation sim,
            BudgetClusterConfig config,
            NetworkConditions conditions,
            long[] clockOffsetsNanos,
            NetworkObserver observer) {
        if (clockOffsetsNanos.length != config.shardCount) {
            throw new IllegalArgumentException(
                    "expected " + config.shardCount + " clock offsets, got " + clockOffsetsNanos.length);
        }
        this.sim = sim;
        this.config = config;
        this.net = new SimNetwork(sim, config.shardCount + 1, conditions, observer);
        this.authority = config.grantPolicy == null
                ? new BudgetAuthority(
                        config.budgetMicros, config.shardCount, config.leaseDurationNanos,
                        config.reclaimMarginNanos)
                : new BudgetAuthority(
                        config.budgetMicros, config.shardCount, config.leaseDurationNanos,
                        config.reclaimMarginNanos, config.grantPolicy);
        this.authorityClock = new NodeClock(sim, 0L);
        this.wallets = new SpendAuthority[config.shardCount];
        this.clocks = new NodeClock[config.shardCount];
        this.clockOffsetsNanos = clockOffsetsNanos.clone();
        this.alive = new boolean[config.shardCount];
        this.incarnations = new long[config.shardCount];
        this.nextRequestAllowedAt = new long[config.shardCount];
        this.nextReportAt = new long[config.shardCount];
        this.engines = new AuctionEngine[config.shardCount];
        this.requests = new AuctionRequest[config.shardCount];
        this.outcomes = new AuctionOutcome[config.shardCount];
        this.minPriceFloorMicros = Math.ceilDiv(
                config.reserveMicros * AuctionRequest.QUALITY_ONE_BPS, config.ourQualityBps);

        for (int shard = 0; shard < config.shardCount; shard++) {
            alive[shard] = true;
            incarnations[shard] = 1L;
            wallets[shard] = new SpendAuthority(shard, 1L);
            clocks[shard] = new NodeClock(sim, clockOffsetsNanos[shard]);
            // One engine per shard, mirroring the thread-confined deployment shape even
            // though the simulation itself is single-threaded.
            engines[shard] = new AuctionEngine(config.competitorCount + 1);
            requests[shard] = new AuctionRequest(config.competitorCount + 1);
            outcomes[shard] = new AuctionOutcome(config.slots);
        }
    }

    public static int nodeOf(int shard) {
        return shard + 1;
    }

    public void start() {
        for (int shard = 0; shard < config.shardCount; shard++) {
            scheduleTraffic(shard);
        }
        scheduleSweep();
    }

    /**
     * Starts traffic and, when configured, spend checkpoints over a known horizon so a pacing
     * test can compare cumulative delivery against the target curve.
     */
    public void start(long runHorizonNanos) {
        this.runHorizonNanos = runHorizonNanos;
        start();
        if (config.checkpointIntervalNanos > 0) {
            scheduleCheckpoint(config.checkpointIntervalNanos);
        }
    }

    /** When false, in-flight traffic ticks finish but do not re-arm. */
    public void setTrafficEnabled(boolean enabled) {
        trafficEnabled = enabled;
        if (enabled) {
            for (int shard = 0; shard < config.shardCount; shard++) {
                if (alive[shard]) {
                    scheduleTraffic(shard);
                }
            }
        }
    }

    /**
     * Runs one search on a shard without re-arming background traffic.
     *
     * <p>Renewal and reporting still run so lease protocol behaviour matches production traffic.
     */
    public SearchResult injectSearch(int shard) {
        checkShard(shard);
        final long now = clocks[shard].nanos();
        final SearchResult result = serveOne(shard, now);
        maybeRenew(shard, now);
        maybeReport(shard, now);
        return result;
    }

    /**
     * Kills a shard's timers without starting a replacement.
     *
     * <p>Background traffic on the shard stops re-arming until {@link #restartShard(int)}.
     */
    public void crashShard(int shard) {
        checkShard(shard);
        sim.crash(nodeOf(shard));
        alive[shard] = false;
    }

    /**
     * Kills a shard's process and starts a replacement.
     *
     * <p>The wallet is discarded rather than carried over, because it lived in memory. The
     * replacement therefore has no authority and no record of what its predecessor spent.
     */
    public void restartShard(int shard) {
        checkShard(shard);
        sim.crash(nodeOf(shard));
        alive[shard] = true;
        incarnations[shard]++;
        wallets[shard] = new SpendAuthority(shard, incarnations[shard]);
        nextRequestAllowedAt[shard] = 0L;
        nextReportAt[shard] = 0L;
        restarts++;
        if (trafficEnabled) {
            scheduleTraffic(shard);
        }
    }

    public SimNetwork network() {
        return net;
    }

    public BudgetAuthority authority() {
        return authority;
    }

    public BudgetClusterConfig config() {
        return config;
    }

    public SpendAuthority wallet(int shard) {
        checkShard(shard);
        return wallets[shard];
    }

    public NodeClock shardClock(int shard) {
        checkShard(shard);
        return clocks[shard];
    }

    public NodeClock authorityClock() {
        return authorityClock;
    }

    public boolean isShardAlive(int shard) {
        checkShard(shard);
        return alive[shard];
    }

    /** Ground truth: money actually committed, tallied by the harness. */
    public long actualSpendMicros() {
        return actualSpendMicros;
    }

    public long overspendMicros() {
        return Math.max(0L, actualSpendMicros - config.budgetMicros);
    }

    public double deliveredFraction() {
        return (double) actualSpendMicros / config.budgetMicros;
    }

    public double overspendFraction() {
        return (double) overspendMicros() / config.budgetMicros;
    }

    public long servedRequests() {
        return servedRequests;
    }

    public long refusedRequests() {
        return refusedRequests;
    }

    /** Requests where our campaign was outranked outright, so there was nothing to spend. */
    public long lostAuctions() {
        return lostAuctions;
    }

    public long restarts() {
        return restarts;
    }

    /** Snapshots of {@code [simNanos, actualSpendMicros]} when checkpointing is enabled. */
    public List<long[]> spendCheckpoints() {
        return spendCheckpoints;
    }

    public ClusterSnapshot snapshot() {
        final List<ShardSnapshot> shards = new ArrayList<>(config.shardCount);
        for (int shard = 0; shard < config.shardCount; shard++) {
            final SpendAuthority wallet = wallets[shard];
            shards.add(new ShardSnapshot(
                    shard,
                    alive[shard],
                    wallet.incarnation(),
                    wallet.remainingMicros(),
                    wallet.leaseId(),
                    wallet.leaseSpentMicros(),
                    wallet.lifetimeSpentMicros(),
                    wallet.leaseExpiresAtNanos(),
                    clockOffsetsNanos[shard],
                    wallet.pendingReleaseId()));
        }

        final int nodes = net.nodeCount();
        final List<ClusterSnapshot.BlockedLink> blocked = new ArrayList<>();
        for (int from = 0; from < nodes; from++) {
            for (int to = 0; to < nodes; to++) {
                if (net.isBlocked(from, to)) {
                    blocked.add(new ClusterSnapshot.BlockedLink(from, to));
                }
            }
        }

        final long spendableRemainder = Math.max(0L, config.budgetMicros - actualSpendMicros);
        return new ClusterSnapshot(
                config.budgetMicros,
                authority.settledMicros(),
                authority.outstandingMicros(),
                authority.headroomMicros(),
                authority.observedSpendMicros(),
                actualSpendMicros,
                overspendMicros(),
                spendableRemainder,
                List.copyOf(shards),
                net.sentCount(),
                net.deliveredCount(),
                net.droppedCount(),
                net.duplicatedCount(),
                net.partitionedCount(),
                servedRequests,
                refusedRequests,
                lostAuctions,
                restarts,
                List.copyOf(blocked),
                sim.now(),
                sim.eventsFired());
    }

    private void scheduleCheckpoint(long atNanos) {
        if (atNanos > runHorizonNanos) {
            return;
        }
        final long delay = atNanos - sim.now();
        if (delay < 0) {
            return;
        }
        sim.schedule(delay, AUTHORITY_NODE, () -> {
            spendCheckpoints.add(new long[] {sim.now(), actualSpendMicros});
            scheduleCheckpoint(atNanos + config.checkpointIntervalNanos);
        });
    }

    /** The authority's expiry sweeper, the only place unilateral reclaim happens. */
    private void scheduleSweep() {
        sim.schedule(config.sweepIntervalNanos, AUTHORITY_NODE, () -> {
            authority.reclaimExpired(authorityClock.nanos());
            scheduleSweep();
        });
    }

    private void scheduleTraffic(int shard) {
        if (!trafficEnabled || !alive[shard]) {
            return;
        }
        final long delay = nextRequestDelay();
        sim.schedule(delay, nodeOf(shard), () -> {
            if (!alive[shard]) {
                return;
            }
            final long now = clocks[shard].nanos();
            serveOne(shard, now);
            maybeRenew(shard, now);
            maybeReport(shard, now);
            if (trafficEnabled && alive[shard]) {
                scheduleTraffic(shard);
            }
        });
    }

    /**
     * Uniform traffic draws from the full interval range. Front-loaded traffic uses the short
     * end early and the long end late, so demand piles up before noon without pacing.
     */
    private long nextRequestDelay() {
        if (!config.frontLoadedTraffic) {
            final long span = config.maxRequestIntervalNanos - config.minRequestIntervalNanos;
            return config.minRequestIntervalNanos + (span == 0 ? 0 : sim.random().nextLong(span + 1));
        }
        final long mid = runHorizonNanos > 0 ? runHorizonNanos / 2 : Long.MAX_VALUE / 2;
        final boolean early = sim.now() < mid;
        final long lo = early ? config.minRequestIntervalNanos : (config.minRequestIntervalNanos + config.maxRequestIntervalNanos) / 2;
        final long hi = early ? (config.minRequestIntervalNanos + config.maxRequestIntervalNanos) / 2 : config.maxRequestIntervalNanos;
        final long span = Math.max(0L, hi - lo);
        return lo + (span == 0 ? 0 : sim.random().nextLong(span + 1));
    }

    /**
     * One request on one shard: a real auction decides whether our campaign wins a slot
     * and what a click costs, then the wallet decides whether it can afford that price.
     *
     * <p>Competitor bids and qualities are drawn fresh per request, and the draws happen
     * unconditionally so the random stream never depends on wallet state — the same
     * discipline {@link io.bidflow.sim.SimNetwork} applies to its own draws. Our campaign
     * always enters the auction; running out of budget shows up as {@code tryReserve}
     * refusing the cleared price, not as absence from the candidate set, so refusals mean
     * the same thing they meant under the synthetic-cost harness.
     */
    private SearchResult serveOne(int shard, long now) {
        final AuctionRequest request = requests[shard].reset(config.slots, config.reserveMicros);
        for (int i = 0; i < config.competitorCount; i++) {
            final long bid = draw(config.minCompetitorBidMicros, config.maxCompetitorBidMicros);
            final int quality = (int) draw(config.minCompetitorQualityBps, config.maxCompetitorQualityBps);
            request.add(COMPETITOR_ID_BASE + i, bid, quality);
        }
        request.add(OUR_CAMPAIGN_ID, config.ourBidMicros, config.ourQualityBps);
        engines[shard].run(request, outcomes[shard]);

        final long cost = ourPriceMicros(outcomes[shard]);
        if (cost == 0) {
            lostAuctions++;
            return new SearchResult(false, false, false, 0L);
        }
        if (wallets[shard].tryReserve(now, cost)) {
            actualSpendMicros += cost;
            servedRequests++;
            return new SearchResult(true, true, false, cost);
        }
        refusedRequests++;
        return new SearchResult(true, false, true, cost);
    }

    /** The price our campaign owes for its slot, or 0 when it was outranked entirely. */
    private static long ourPriceMicros(AuctionOutcome outcome) {
        for (int k = 0; k < outcome.size(); k++) {
            if (outcome.campaignId(k) == OUR_CAMPAIGN_ID) {
                // Never zero for a winner: the positive reserve puts a floor under it.
                return outcome.priceMicros(k);
            }
        }
        return 0L;
    }

    private long draw(long minInclusive, long maxInclusive) {
        final long span = maxInclusive - minInclusive;
        return minInclusive + (span == 0 ? 0 : sim.random().nextLong(span + 1));
    }

    /**
     * Asks for the next lease, settling whatever figure is ready to be settled.
     *
     * <p>The healthy path is a prefetch: the wallet keeps spending on its live lease while
     * the request is in flight, and installing the grant displaces that lease into the
     * pending-release slot, whose final figure rides on the <em>next</em> request. Sealing
     * happens only when there is nothing left to prefetch for — the wallet is empty or the
     * lease has already expired. Rate-limited on the shard's own clock rather than guarded
     * by an in-flight flag: a flag would strand the shard permanently if the reply were
     * lost, whereas a cooldown retries on the next traffic tick — and the authority
     * retransmits its live grant on a retry rather than minting another.
     */
    private void maybeRenew(int shard, long now) {
        final SpendAuthority wallet = wallets[shard];
        if (!wallet.needsLease(now, config.lowWaterMicros, config.renewAheadNanos)) {
            return;
        }
        if (now < nextRequestAllowedAt[shard]) {
            return;
        }
        nextRequestAllowedAt[shard] = now + config.requestCooldownNanos;

        final long incarnation = wallet.incarnation();
        final long heldId = wallet.leaseId();
        final long settleId;
        final long settleSpent;
        if (wallet.pendingReleaseId() != Lease.NONE) {
            settleId = wallet.pendingReleaseId();
            settleSpent = wallet.pendingReleaseSpentMicros();
        } else if (wallet.isExpired(now) || wallet.isSealed()
                || wallet.remainingMicros() < minPriceFloorMicros) {
            // Nothing left worth prefetching for: the lease is dead, or its remainder is
            // below the cheapest possible price. Sealing here is also what breaks the
            // otherwise-endless retransmit loop for a drained never-expiring lease.
            settleId = wallet.leaseId();
            settleSpent = wallet.sealForRenewal();
        } else {
            // Pure prefetch: nothing to settle yet, keep spending until the grant lands.
            settleId = Lease.NONE;
            settleSpent = 0L;
        }

        net.send(nodeOf(shard), AUTHORITY_NODE, "lease-req s" + shard + " i" + incarnation, () -> {
            final Lease lease = authority.requestLease(
                    shard, incarnation, heldId, settleId, settleSpent, config.leaseMicros,
                    authorityClock.nanos());
            if (lease == null) {
                return;
            }
            net.send(AUTHORITY_NODE, nodeOf(shard), "lease s" + shard + " #" + lease.leaseId(), () -> {
                final SpendAuthority current = wallets[shard];
                if (current.incarnation() != incarnation) {
                    return;
                }
                if (current.installLease(lease, clocks[shard].nanos())
                        && current.pendingReleaseId() != Lease.NONE) {
                    // Prompt release: send the displaced lease's final figure right away,
                    // so its remainder returns without waiting for the next renewal. If
                    // this message is lost, the next renewal names the same record and a
                    // periodic report carries the same figure — all three are idempotent.
                    final long releaseId = current.pendingReleaseId();
                    final long releaseSpent = current.pendingReleaseSpentMicros();
                    net.send(nodeOf(shard), AUTHORITY_NODE, "release s" + shard + " #" + releaseId,
                            () -> authority.releaseSealed(shard, incarnation, releaseId, releaseSpent));
                }
            });
        });
    }

    /**
     * Tells the authority how much of the live lease has gone.
     *
     * <p>Irrelevant while the shard is healthy, since the lease's full value is already reserved.
     * It matters only if this shard later becomes unreachable and its lease has to be reclaimed
     * without its cooperation — the last report received is then all that stands between the
     * authority and re-leasing money that was already spent.
     */
    private void maybeReport(int shard, long now) {
        if (now < nextReportAt[shard]) {
            return;
        }
        nextReportAt[shard] = now + config.reportIntervalNanos;

        final SpendAuthority wallet = wallets[shard];
        if (wallet.leaseId() == Lease.NONE) {
            return;
        }
        final long incarnation = wallet.incarnation();

        // The pending release's figure normally travels on the next lease request, but if
        // that request never arrives this report is what the sweeper will settle at. A
        // report for an already-settled lease is simply ignored.
        final long pendingId = wallet.pendingReleaseId();
        if (pendingId != Lease.NONE) {
            final long pendingSpent = wallet.pendingReleaseSpentMicros();
            net.send(nodeOf(shard), AUTHORITY_NODE, "report s" + shard + " #" + pendingId, () ->
                    authority.recordReport(shard, incarnation, pendingId, pendingSpent));
        }

        final long leaseId = wallet.leaseId();
        final long spent = wallet.leaseSpentMicros();
        net.send(nodeOf(shard), AUTHORITY_NODE, "report s" + shard + " #" + leaseId, () ->
                authority.recordReport(shard, incarnation, leaseId, spent));
    }

    private void checkShard(int shard) {
        if (shard < 0 || shard >= config.shardCount) {
            throw new IllegalArgumentException(
                    "shard must be in [0, " + config.shardCount + "), was " + shard);
        }
    }
}
