package io.bidflow.serving;

/**
 * One campaign's budget and bid parameters for the serving layer.
 *
 * @param campaignId campaign id used in auction candidates
 * @param bidMicros maximum bid per click
 * @param dailyBudgetMicros total budget the authority may lease out
 * @param leaseMicros face value requested per lease
 * @param leaseDurationNanos lease lifetime on the authority clock
 * @param reclaimMarginNanos reclaim margin, or {@link io.bidflow.budget.BudgetAuthority#NEVER_RECLAIM}
 */
public record CampaignBudgetConfig(
        long campaignId,
        long bidMicros,
        long dailyBudgetMicros,
        long leaseMicros,
        long leaseDurationNanos,
        long reclaimMarginNanos) {

    public CampaignBudgetConfig {
        if (bidMicros < 0) {
            throw new IllegalArgumentException("bidMicros must not be negative, was " + bidMicros);
        }
        if (dailyBudgetMicros < 0) {
            throw new IllegalArgumentException(
                    "dailyBudgetMicros must not be negative, was " + dailyBudgetMicros);
        }
        if (leaseMicros <= 0) {
            throw new IllegalArgumentException("leaseMicros must be positive, was " + leaseMicros);
        }
        if (leaseDurationNanos <= 0) {
            throw new IllegalArgumentException(
                    "leaseDurationNanos must be positive, was " + leaseDurationNanos);
        }
        if (reclaimMarginNanos < 0) {
            throw new IllegalArgumentException(
                    "reclaimMarginNanos must not be negative, was " + reclaimMarginNanos);
        }
    }
}
