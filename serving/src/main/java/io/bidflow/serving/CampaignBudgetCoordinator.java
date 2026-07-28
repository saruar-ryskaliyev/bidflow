package io.bidflow.serving;

import io.bidflow.budget.BudgetAuthority;
import io.bidflow.budget.Lease;
import io.bidflow.budget.LeaseGrantPolicy;
import io.bidflow.budget.UnpacedGrantPolicy;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Single logical owner of per-campaign {@link BudgetAuthority} instances.
 *
 * <p>Lease grants, releases, reports, and reclaim sweeps run here — off the auction request
 * path. Serving shards ask asynchronously and spend from local wallets only.
 *
 * <p><b>Not thread-safe.</b> Drive from one coordinator thread.
 */
public final class CampaignBudgetCoordinator {

    private final Map<Long, BudgetAuthority> authorities = new LinkedHashMap<>();
    private final Map<Long, CampaignBudgetConfig> configs = new LinkedHashMap<>();
    private final int shardCount;
    private final LeaseGrantPolicy grantPolicy;

    public CampaignBudgetCoordinator(int shardCount, Collection<CampaignBudgetConfig> campaigns) {
        this(shardCount, campaigns, UnpacedGrantPolicy.INSTANCE);
    }

    public CampaignBudgetCoordinator(
            int shardCount, Collection<CampaignBudgetConfig> campaigns, LeaseGrantPolicy grantPolicy) {
        if (shardCount <= 0) {
            throw new IllegalArgumentException("shardCount must be positive, was " + shardCount);
        }
        this.shardCount = shardCount;
        this.grantPolicy = Objects.requireNonNull(grantPolicy, "grantPolicy");
        for (CampaignBudgetConfig config : campaigns) {
            configs.put(config.campaignId(), config);
            authorities.put(
                    config.campaignId(),
                    new BudgetAuthority(
                            config.dailyBudgetMicros(),
                            shardCount,
                            config.leaseDurationNanos(),
                            config.reclaimMarginNanos(),
                            grantPolicy));
        }
    }

    public int shardCount() {
        return shardCount;
    }

    public CampaignBudgetConfig config(long campaignId) {
        return configs.get(campaignId);
    }

    public Collection<CampaignBudgetConfig> configs() {
        return configs.values();
    }

    public BudgetAuthority authority(long campaignId) {
        return authorities.get(campaignId);
    }

    public Lease requestLease(
            long campaignId,
            int shardId,
            long incarnation,
            long heldLeaseId,
            long sealedLeaseId,
            long sealedSpentMicros,
            long nowNanos) {
        final BudgetAuthority authority = authorities.get(campaignId);
        final CampaignBudgetConfig config = configs.get(campaignId);
        if (authority == null || config == null) {
            return null;
        }
        return authority.requestLease(
                shardId,
                incarnation,
                heldLeaseId,
                sealedLeaseId,
                sealedSpentMicros,
                config.leaseMicros(),
                nowNanos);
    }

    public void releaseSealed(
            long campaignId, int shardId, long incarnation, long leaseId, long finalSpentMicros) {
        final BudgetAuthority authority = authorities.get(campaignId);
        if (authority != null) {
            authority.releaseSealed(shardId, incarnation, leaseId, finalSpentMicros);
        }
    }

    public void recordReport(
            long campaignId, int shardId, long incarnation, long leaseId, long spentSoFarMicros) {
        final BudgetAuthority authority = authorities.get(campaignId);
        if (authority != null) {
            authority.recordReport(shardId, incarnation, leaseId, spentSoFarMicros);
        }
    }

    public int reclaimExpired(long nowNanos) {
        int total = 0;
        for (BudgetAuthority authority : authorities.values()) {
            total += authority.reclaimExpired(nowNanos);
        }
        return total;
    }

    public LeaseGrantPolicy grantPolicy() {
        return grantPolicy;
    }
}
