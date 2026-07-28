package io.bidflow.budget;

import io.bidflow.auction.AuctionEngine;
import io.bidflow.auction.AuctionOutcome;
import io.bidflow.auction.AuctionRequest;
import io.bidflow.sim.NetworkConditions;
import io.bidflow.sim.NodeClock;
import io.bidflow.sim.SimNetwork;
import io.bidflow.sim.Simulation;

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
final class BudgetCluster {

    static final int AUTHORITY_NODE = 0;
    private static final long MILLIS = 1_000_000L;

    /** Mutable and fluent, because the sweep varies several of these per run. */
    static final class Config {
        int shardCount = 8;
        long budgetMicros = 10_000_000L;

        /** Face value requested per lease. Also the cap on what one lost lease can cost. */
        long leaseMicros = 200_000L;

        long leaseDurationNanos = 200 * MILLIS;
        long reclaimMarginNanos = BudgetAuthority.NEVER_RECLAIM;

        /** Renew once the wallet drops this low, or this long before the lease expires. */
        long lowWaterMicros = 40_000L;
        long renewAheadNanos = 20 * MILLIS;

        long requestCooldownNanos = 25 * MILLIS;
        long reportIntervalNanos = 50 * MILLIS;
        long sweepIntervalNanos = 25 * MILLIS;

        long minRequestIntervalNanos = 500_000L;
        long maxRequestIntervalNanos = 1_500_000L;

        /** The auction each request runs. Our campaign competes against seeded rivals. */
        int slots = 3;

        /** Kept positive so a won slot always carries a positive price. */
        long reserveMicros = 100L;

        int competitorCount = 6;
        long minCompetitorBidMicros = 100L;
        long maxCompetitorBidMicros = 1_000L;
        int minCompetitorQualityBps = 2_000;
        int maxCompetitorQualityBps = 10_000;
        long ourBidMicros = 800L;
        int ourQualityBps = 8_000;

        Config shardCount(int value) {
            shardCount = value;
            return this;
        }

        Config budgetMicros(long value) {
            budgetMicros = value;
            return this;
        }

        Config leaseMicros(long value) {
            leaseMicros = value;
            return this;
        }

        Config leaseDurationNanos(long value) {
            leaseDurationNanos = value;
            return this;
        }

        Config reclaimMarginNanos(long value) {
            reclaimMarginNanos = value;
            return this;
        }

        Config lowWaterMicros(long value) {
            lowWaterMicros = value;
            return this;
        }
    }

    /** The campaign whose budget is enforced; competitors carry ids from 100 upward. */
    private static final long OUR_CAMPAIGN_ID = 1L;

    private static final long COMPETITOR_ID_BASE = 100L;

    private final Simulation sim;
    private final SimNetwork net;
    private final Config config;
    private final BudgetAuthority authority;
    private final NodeClock authorityClock;
    private final SpendAuthority[] wallets;
    private final NodeClock[] clocks;
    private final long[] incarnations;
    private final long[] nextRequestAllowedAt;
    private final long[] nextReportAt;
    private final AuctionEngine[] engines;
    private final AuctionRequest[] requests;
    private final AuctionOutcome[] outcomes;

    /** The cheapest price a won slot can carry; below this a wallet cannot spend at all. */
    private final long minPriceFloorMicros;

    private long actualSpendMicros;
    private long servedRequests;
    private long refusedRequests;
    private long lostAuctions;
    private long restarts;

    BudgetCluster(Simulation sim, Config config, NetworkConditions conditions) {
        this(sim, config, conditions, new long[config.shardCount]);
    }

    BudgetCluster(Simulation sim, Config config, NetworkConditions conditions, long[] clockOffsetsNanos) {
        if (clockOffsetsNanos.length != config.shardCount) {
            throw new IllegalArgumentException(
                    "expected " + config.shardCount + " clock offsets, got " + clockOffsetsNanos.length);
        }
        this.sim = sim;
        this.config = config;
        this.net = new SimNetwork(sim, config.shardCount + 1, conditions);
        this.authority = new BudgetAuthority(
                config.budgetMicros, config.shardCount, config.leaseDurationNanos, config.reclaimMarginNanos);
        this.authorityClock = new NodeClock(sim, 0L);
        this.wallets = new SpendAuthority[config.shardCount];
        this.clocks = new NodeClock[config.shardCount];
        this.incarnations = new long[config.shardCount];
        this.nextRequestAllowedAt = new long[config.shardCount];
        this.nextReportAt = new long[config.shardCount];
        this.engines = new AuctionEngine[config.shardCount];
        this.requests = new AuctionRequest[config.shardCount];
        this.outcomes = new AuctionOutcome[config.shardCount];
        this.minPriceFloorMicros = Math.ceilDiv(
                config.reserveMicros * AuctionRequest.QUALITY_ONE_BPS, config.ourQualityBps);

        for (int shard = 0; shard < config.shardCount; shard++) {
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

    void start() {
        for (int shard = 0; shard < config.shardCount; shard++) {
            scheduleTraffic(shard);
        }
        scheduleSweep();
    }

    /**
     * Kills a shard's process and starts a replacement.
     *
     * <p>The wallet is discarded rather than carried over, because it lived in memory. The
     * replacement therefore has no authority and no record of what its predecessor spent.
     */
    void restartShard(int shard) {
        sim.crash(nodeOf(shard));
        incarnations[shard]++;
        wallets[shard] = new SpendAuthority(shard, incarnations[shard]);
        nextRequestAllowedAt[shard] = 0L;
        nextReportAt[shard] = 0L;
        restarts++;
        scheduleTraffic(shard);
    }

    /** The authority's expiry sweeper, the only place unilateral reclaim happens. */
    private void scheduleSweep() {
        sim.schedule(config.sweepIntervalNanos, AUTHORITY_NODE, () -> {
            authority.reclaimExpired(authorityClock.nanos());
            scheduleSweep();
        });
    }

    private void scheduleTraffic(int shard) {
        final long span = config.maxRequestIntervalNanos - config.minRequestIntervalNanos;
        final long delay = config.minRequestIntervalNanos + (span == 0 ? 0 : sim.random().nextLong(span + 1));
        sim.schedule(delay, nodeOf(shard), () -> {
            final long now = clocks[shard].nanos();
            serveOne(shard, now);
            maybeRenew(shard, now);
            maybeReport(shard, now);
            scheduleTraffic(shard);
        });
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
    private void serveOne(int shard, long now) {
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
            return;
        }
        if (wallets[shard].tryReserve(now, cost)) {
            actualSpendMicros += cost;
            servedRequests++;
        } else {
            refusedRequests++;
        }
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

    static int nodeOf(int shard) {
        return shard + 1;
    }

    SimNetwork network() {
        return net;
    }

    BudgetAuthority authority() {
        return authority;
    }

    Config config() {
        return config;
    }

    /** Ground truth: money actually committed, tallied by the harness. */
    long actualSpendMicros() {
        return actualSpendMicros;
    }

    long overspendMicros() {
        return Math.max(0L, actualSpendMicros - config.budgetMicros);
    }

    double deliveredFraction() {
        return (double) actualSpendMicros / config.budgetMicros;
    }

    double overspendFraction() {
        return (double) overspendMicros() / config.budgetMicros;
    }

    long servedRequests() {
        return servedRequests;
    }

    long refusedRequests() {
        return refusedRequests;
    }

    /** Requests where our campaign was outranked outright, so there was nothing to spend. */
    long lostAuctions() {
        return lostAuctions;
    }

    long restarts() {
        return restarts;
    }
}
