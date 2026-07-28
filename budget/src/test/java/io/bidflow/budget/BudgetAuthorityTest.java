package io.bidflow.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class BudgetAuthorityTest {

    private static final long LEASE_DURATION = 1_000L;
    private static final long MARGIN = 100L;
    private static final long T0 = 10_000L;

    private static BudgetAuthority bank(long budget, int shards) {
        return new BudgetAuthority(budget, shards, LEASE_DURATION, MARGIN);
    }

    /** Convenience for the common case of a first lease with nothing to settle. */
    private static Lease firstLease(BudgetAuthority bank, int shard, long wanted, long now) {
        return bank.requestLease(shard, 1L, Lease.NONE, 0L, wanted, now);
    }

    @Nested
    @DisplayName("leasing")
    class Leasing {

        @Test
        @DisplayName("issues a lease and reserves its full face value")
        void issuesAndReserves() {
            final BudgetAuthority bank = bank(1_000L, 2);
            final Lease lease = firstLease(bank, 0, 300L, T0);

            assertThat(lease).isNotNull();
            assertThat(lease.amountMicros()).isEqualTo(300L);
            assertThat(lease.expiresAtNanos()).isEqualTo(T0 + LEASE_DURATION);
            // Reserved in full, because until told otherwise the authority must assume all of it
            // will be spent.
            assertThat(bank.outstandingMicros()).isEqualTo(300L);
            assertThat(bank.headroomMicros()).isEqualTo(700L);
        }

        @Test
        @DisplayName("never commits more than the budget")
        void neverCommitsBeyondTheBudget() {
            final BudgetAuthority bank = bank(1_000L, 4);
            for (int shard = 0; shard < 4; shard++) {
                bank.requestLease(shard, 1L, Lease.NONE, 0L, 800L, T0);
            }

            assertThat(bank.settledMicros() + bank.outstandingMicros()).isEqualTo(1_000L);
            assertThat(bank.headroomMicros()).isZero();
            assertThat(bank.leasesExhausted()).isPositive();
        }

        @Test
        @DisplayName("issues the partial remainder rather than refusing")
        void issuesThePartialRemainder() {
            final BudgetAuthority bank = bank(1_000L, 2);
            firstLease(bank, 0, 900L, T0);

            assertThat(firstLease(bank, 1, 500L, T0).amountMicros()).isEqualTo(100L);
        }

        @Test
        @DisplayName("returns nothing when the budget is fully committed")
        void refusesWhenExhausted() {
            final BudgetAuthority bank = bank(100L, 2);
            firstLease(bank, 0, 100L, T0);

            assertThat(firstLease(bank, 1, 50L, T0)).isNull();
        }

        @Test
        @DisplayName("gives each renewal a distinct increasing id")
        void leaseIdsIncrease() {
            // Ids increase across renewals — a retry without a seal retransmits instead,
            // which the grant-retransmission tests pin down separately.
            final BudgetAuthority bank = bank(1_000L, 1);
            final Lease first = firstLease(bank, 0, 100L, T0);
            final Lease second = bank.requestLease(0, 1L, first.leaseId(), 50L, 100L, T0);
            final Lease third = bank.requestLease(0, 1L, second.leaseId(), 50L, 100L, T0);

            assertThat(second.leaseId()).isGreaterThan(first.leaseId());
            assertThat(third.leaseId()).isGreaterThan(second.leaseId());
        }
    }

    @Nested
    @DisplayName("voluntary release")
    class VoluntaryRelease {

        @Test
        @DisplayName("settles the reported spend and returns the remainder")
        void releaseReturnsTheRemainder() {
            final BudgetAuthority bank = bank(1_000L, 1);
            final Lease lease = firstLease(bank, 0, 500L, T0);

            bank.requestLease(0, 1L, lease.leaseId(), 340L, 500L, T0);

            // 340 spent, so 160 comes back and is available to lease again.
            assertThat(bank.settledMicros()).isEqualTo(340L);
            assertThat(bank.releasedMicros()).isEqualTo(160L);
            assertThat(bank.settledMicros() + bank.outstandingMicros()).isEqualTo(840L);
        }

        @Test
        @DisplayName("releasing the same lease twice does not double-count")
        void releaseIsIdempotent() {
            final BudgetAuthority bank = bank(1_000L, 1);
            final Lease lease = firstLease(bank, 0, 500L, T0);

            bank.requestLease(0, 1L, lease.leaseId(), 340L, 100L, T0);
            final long settledOnce = bank.settledMicros();
            bank.requestLease(0, 1L, lease.leaseId(), 340L, 100L, T0);

            assertThat(bank.settledMicros()).isEqualTo(settledOnce);
        }

        @Test
        @DisplayName("cannot be talked into settling more than the lease was worth")
        void releaseIsCappedByTheLease() {
            final BudgetAuthority bank = bank(1_000L, 1);
            final Lease lease = firstLease(bank, 0, 500L, T0);

            bank.requestLease(0, 1L, lease.leaseId(), 99_999L, 0L, T0);
            assertThat(bank.settledMicros()).isEqualTo(500L);
        }

        @Test
        @DisplayName("never settles below what was already reported")
        void releaseCannotUndercutAReport() {
            final BudgetAuthority bank = bank(1_000L, 1);
            final Lease lease = firstLease(bank, 0, 500L, T0);
            bank.recordReport(0, 1L, lease.leaseId(), 400L);

            // A release claiming less than a report already seen would free money known to be
            // spent, so the higher figure wins.
            bank.requestLease(0, 1L, lease.leaseId(), 100L, 0L, T0);
            assertThat(bank.settledMicros()).isEqualTo(400L);
        }
    }

    @Nested
    @DisplayName("unilateral reclaim")
    class UnilateralReclaim {

        @Test
        @DisplayName("waits for the margin to elapse before taking a lease back")
        void respectsTheMargin() {
            final BudgetAuthority bank = bank(1_000L, 1);
            final Lease lease = firstLease(bank, 0, 500L, T0);
            final long expiry = lease.expiresAtNanos();

            assertThat(bank.reclaimExpired(expiry)).isZero();
            assertThat(bank.reclaimExpired(expiry + MARGIN - 1)).isZero();
            assertThat(bank.reclaimExpired(expiry + MARGIN)).isEqualTo(1);
        }

        @Test
        @DisplayName("settles at the last reported figure and frees the rest")
        void settlesAtTheLastReport() {
            final BudgetAuthority bank = bank(1_000L, 1);
            final Lease lease = firstLease(bank, 0, 500L, T0);
            bank.recordReport(0, 1L, lease.leaseId(), 120L);

            bank.reclaimExpired(lease.expiresAtNanos() + MARGIN);

            // The authority is betting the missing 380 was never spent. That bet is the entire
            // risk of unilateral reclaim, and it is bounded by the size of the lease.
            assertThat(bank.settledMicros()).isEqualTo(120L);
            assertThat(bank.reclaimedMicros()).isEqualTo(380L);
            assertThat(bank.headroomMicros()).isEqualTo(880L);
        }

        @Test
        @DisplayName("frees the whole lease when no report ever arrived")
        void freesEverythingWhenUninformed() {
            final BudgetAuthority bank = bank(1_000L, 1);
            final Lease lease = firstLease(bank, 0, 500L, T0);

            bank.reclaimExpired(lease.expiresAtNanos() + MARGIN);

            // The worst case, and the reason lease size is the control for this exposure.
            assertThat(bank.reclaimedMicros()).isEqualTo(500L);
            assertThat(bank.headroomMicros()).isEqualTo(1_000L);
        }

        @Test
        @DisplayName("never reclaims when configured not to")
        void neverReclaimDisablesTheSweeper() {
            final BudgetAuthority bank =
                    new BudgetAuthority(1_000L, 1, LEASE_DURATION, BudgetAuthority.NEVER_RECLAIM);
            firstLease(bank, 0, 500L, T0);

            assertThat(bank.reclaimExpired(Long.MAX_VALUE)).isZero();
            assertThat(bank.outstandingMicros()).isEqualTo(500L);
        }

        @Test
        @DisplayName("leaves unexpired leases alone")
        void leavesLiveLeasesAlone() {
            final BudgetAuthority bank = bank(1_000L, 2);
            firstLease(bank, 0, 200L, T0);
            firstLease(bank, 1, 200L, T0 + 5_000L);

            assertThat(bank.reclaimExpired(T0 + LEASE_DURATION + MARGIN)).isEqualTo(1);
            assertThat(bank.outstandingLeaseCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("grant retransmission")
    class GrantRetransmission {

        @Test
        @DisplayName("a retry gets the previous grant back rather than a second lease")
        void retryGetsTheSameGrant() {
            final BudgetAuthority bank = bank(1_000L, 1);
            final Lease first = firstLease(bank, 0, 300L, T0);

            // The reply was slow or lost, so the shard asks again without naming a seal.
            final Lease retry = bank.requestLease(0, 1L, Lease.NONE, 0L, 300L, T0 + 10);

            assertThat(retry.leaseId()).isEqualTo(first.leaseId());
            assertThat(retry.amountMicros()).isEqualTo(first.amountMicros());
            assertThat(bank.leasesIssued()).isEqualTo(1L);
            assertThat(bank.leasesRetransmitted()).isEqualTo(1L);
            // Nothing extra was reserved: retries are free instead of stranding face value.
            assertThat(bank.outstandingLeaseCount()).isEqualTo(1);
            assertThat(bank.outstandingMicros()).isEqualTo(300L);
        }

        @Test
        @DisplayName("an expired pending grant is not retransmitted")
        void expiredGrantIsNotRetransmitted() {
            final BudgetAuthority bank = bank(1_000L, 1);
            final Lease first = firstLease(bank, 0, 300L, T0);

            // Past expiry the old grant is useless to the holder; it waits for the
            // sweeper while a fresh lease is minted from the remaining headroom.
            final Lease retry = bank.requestLease(
                    0, 1L, Lease.NONE, 0L, 300L, first.expiresAtNanos());

            assertThat(retry.leaseId()).isGreaterThan(first.leaseId());
            assertThat(bank.leasesRetransmitted()).isZero();
            assertThat(bank.outstandingLeaseCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("a renewal that seals the retransmitted grant proceeds normally")
        void renewalAfterRetransmissionMintsFresh() {
            final BudgetAuthority bank = bank(1_000L, 1);
            final Lease first = firstLease(bank, 0, 300L, T0);
            bank.requestLease(0, 1L, Lease.NONE, 0L, 300L, T0 + 10);

            final Lease next = bank.requestLease(0, 1L, first.leaseId(), 120L, 300L, T0 + 20);

            assertThat(next.leaseId()).isGreaterThan(first.leaseId());
            assertThat(bank.settledMicros()).isEqualTo(120L);
            assertThat(bank.outstandingLeaseCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("reports")
    class Reports {

        @Test
        @DisplayName("keeps the highest figure and ignores stale ones")
        void reportsAreMonotonic() {
            final BudgetAuthority bank = bank(1_000L, 1);
            final Lease lease = firstLease(bank, 0, 500L, T0);

            bank.recordReport(0, 1L, lease.leaseId(), 300L);
            bank.recordReport(0, 1L, lease.leaseId(), 120L);
            bank.reclaimExpired(lease.expiresAtNanos() + MARGIN);

            assertThat(bank.settledMicros()).isEqualTo(300L);
        }

        @Test
        @DisplayName("ignores a report for a lease it does not know")
        void ignoresUnknownLease() {
            final BudgetAuthority bank = bank(1_000L, 1);
            firstLease(bank, 0, 500L, T0);

            bank.recordReport(0, 1L, 999L, 400L);
            bank.reclaimExpired(T0 + LEASE_DURATION + MARGIN);
            assertThat(bank.settledMicros()).isZero();
        }
    }

    @Nested
    @DisplayName("restarts")
    class Restarts {

        @Test
        @DisplayName("a restarted shard gets a fresh lease and no inherited authority")
        void restartStartsFresh() {
            final BudgetAuthority bank = bank(1_000L, 1);
            firstLease(bank, 0, 400L, T0);

            final Lease afterRestart = bank.requestLease(0, 2L, Lease.NONE, 0L, 100L, T0);
            assertThat(afterRestart.amountMicros()).isEqualTo(100L);
            assertThat(bank.restartsObserved()).isEqualTo(1L);
        }

        @Test
        @DisplayName("the dead process's lease is left for the sweeper, not written off")
        void deadLeaseIsSweptNotDiscarded() {
            final BudgetAuthority bank = bank(1_000L, 1);
            final Lease dead = firstLease(bank, 0, 400L, T0);
            bank.requestLease(0, 2L, Lease.NONE, 0L, 100L, T0);

            assertThat(bank.outstandingLeaseCount()).isEqualTo(2);

            // A crashed process has certainly stopped spending, which makes its lease the safest
            // possible thing to reclaim.
            bank.reclaimExpired(dead.expiresAtNanos() + MARGIN);
            assertThat(bank.reclaimedMicros()).isEqualTo(500L);
        }

        @Test
        @DisplayName("refuses a request from a process already replaced")
        void supersededRequestIsRefused() {
            final BudgetAuthority bank = bank(1_000L, 1);
            bank.requestLease(0, 2L, Lease.NONE, 0L, 100L, T0);

            assertThat(bank.requestLease(0, 1L, Lease.NONE, 0L, 100L, T0)).isNull();
            assertThat(bank.leasesSuperseded()).isEqualTo(1L);
        }
    }

    @Test
    @DisplayName("rejects an unknown shard")
    void rejectsUnknownShard() {
        final BudgetAuthority bank = bank(1_000L, 2);
        assertThatThrownBy(() -> bank.requestLease(2, 1L, Lease.NONE, 0L, 100L, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shardId");
    }

    @Test
    @DisplayName("rejects a non-positive lease duration")
    void rejectsBadLeaseDuration() {
        assertThatThrownBy(() -> new BudgetAuthority(1_000L, 1, 0L, MARGIN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("leaseDurationNanos");
    }
}
