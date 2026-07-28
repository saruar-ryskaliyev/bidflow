package io.bidflow.budget;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins that {@link BudgetAuthority} applies the grant policy before minting and that
 * {@link BudgetAuthority#observedSpendMicros()} sees reported live-lease spend.
 */
class BudgetAuthorityPacingTest {

    private static final long LEASE_DURATION = 1_000L;
    private static final long MARGIN = 100L;
    private static final long T0 = 10_000L;
    private static final long DAY = 1_000_000L;

    @Test
    @DisplayName("unpaced authority is unchanged from the historical default")
    void unpacedMatchesHistorical() {
        final BudgetAuthority bank = new BudgetAuthority(1_000L, 1, LEASE_DURATION, MARGIN);
        final Lease lease = bank.requestLease(0, 1L, Lease.NONE, Lease.NONE, 0L, 300L, T0);
        assertThat(lease.amountMicros()).isEqualTo(300L);
        assertThat(bank.leasesPaced()).isZero();
    }

    @Test
    @DisplayName("pacing reduces a grant when observed spend is ahead of the target")
    void pacingReducesGrant() {
        final PacingController pacing = new PacingController(
                T0, DAY, 1_000L, 20_000, PacingController.BPS);
        final BudgetAuthority bank =
                new BudgetAuthority(1_000L, 1, LEASE_DURATION, MARGIN, pacing);

        // Seed observed spend by issuing, reporting, and leaving the lease live.
        final Lease first = bank.requestLease(0, 1L, Lease.NONE, Lease.NONE, 0L, 400L, T0);
        bank.recordReport(0, 1L, first.leaseId(), 400L);
        assertThat(bank.observedSpendMicros()).isEqualTo(400L);

        // Near the start of the day the target is near zero, so the next grant is cut.
        final Lease second = bank.requestLease(
                0, 1L, first.leaseId(), Lease.NONE, 0L, 400L, T0 + DAY / 100);
        assertThat(second).isNotNull();
        assertThat(second.amountMicros()).isLessThan(400L);
        assertThat(bank.leasesPaced()).isPositive();
    }

    @Test
    @DisplayName("observed spend includes reported figures on live leases")
    void observedIncludesLiveReports() {
        final BudgetAuthority bank = new BudgetAuthority(1_000L, 1, LEASE_DURATION, MARGIN);
        final Lease lease = bank.requestLease(0, 1L, Lease.NONE, Lease.NONE, 0L, 500L, T0);
        assertThat(bank.observedSpendMicros()).isZero();
        bank.recordReport(0, 1L, lease.leaseId(), 120L);
        assertThat(bank.observedSpendMicros()).isEqualTo(120L);
        bank.releaseSealed(0, 1L, lease.leaseId(), 200L);
        assertThat(bank.observedSpendMicros()).isEqualTo(200L);
        assertThat(bank.settledMicros()).isEqualTo(200L);
    }
}
