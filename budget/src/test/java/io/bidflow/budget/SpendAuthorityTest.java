package io.bidflow.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SpendAuthorityTest {

    private static final long T0 = 1_000L;
    private static final long EXPIRY = 10_000L;

    private static SpendAuthority walletWith(long amount) {
        final SpendAuthority wallet = new SpendAuthority(0, 1L);
        wallet.installLease(new Lease(1L, amount, EXPIRY), T0);
        return wallet;
    }

    @Nested
    @DisplayName("spending")
    class Spending {

        @Test
        @DisplayName("a wallet with no lease can spend nothing")
        void startsEmpty() {
            final SpendAuthority wallet = new SpendAuthority(0, 1L);
            assertThat(wallet.leaseId()).isEqualTo(Lease.NONE);
            assertThat(wallet.tryReserve(T0, 1L)).isFalse();
        }

        @Test
        @DisplayName("spends up to the lease and then refuses")
        void spendsUpToTheLease() {
            final SpendAuthority wallet = walletWith(1_000L);

            assertThat(wallet.tryReserve(T0, 600L)).isTrue();
            assertThat(wallet.tryReserve(T0, 400L)).isTrue();
            assertThat(wallet.tryReserve(T0, 1L)).isFalse();
            assertThat(wallet.leaseSpentMicros()).isEqualTo(1_000L);
            assertThat(wallet.remainingMicros()).isZero();
        }

        @Test
        @DisplayName("a refused spend commits nothing")
        void refusedSpendIsNotPartiallyApplied() {
            final SpendAuthority wallet = walletWith(100L);

            assertThat(wallet.tryReserve(T0, 101L)).isFalse();
            assertThat(wallet.leaseSpentMicros()).isZero();
            assertThat(wallet.tryReserve(T0, 100L)).isTrue();
        }

        @Test
        @DisplayName("an enormous request cannot overflow past the check")
        void hugeRequestDoesNotOverflow() {
            final SpendAuthority wallet = walletWith(100L);
            assertThat(wallet.tryReserve(T0, Long.MAX_VALUE)).isFalse();
            assertThat(wallet.leaseSpentMicros()).isZero();
        }

        @Test
        @DisplayName("stops spending once its own clock passes the deadline")
        void stopsAtExpiry() {
            final SpendAuthority wallet = walletWith(1_000L);

            assertThat(wallet.tryReserve(EXPIRY - 1, 10L)).isTrue();
            // Refusing here is what makes the authority's reclaim sound.
            assertThat(wallet.tryReserve(EXPIRY, 10L)).isFalse();
            assertThat(wallet.tryReserve(EXPIRY + 5_000, 10L)).isFalse();
            assertThat(wallet.isExpired(EXPIRY)).isTrue();
        }

        @Test
        @DisplayName("accumulates spend across successive leases")
        void lifetimeSpendAccumulates() {
            final SpendAuthority wallet = walletWith(500L);
            wallet.tryReserve(T0, 300L);
            wallet.sealForRenewal();
            wallet.installLease(new Lease(2L, 500L, EXPIRY), T0);
            wallet.tryReserve(T0, 200L);

            assertThat(wallet.leaseSpentMicros()).isEqualTo(200L);
            assertThat(wallet.lifetimeSpentMicros()).isEqualTo(500L);
        }

        @Test
        @DisplayName("rejects a negative spend")
        void rejectsNegativeSpend() {
            final SpendAuthority wallet = walletWith(100L);
            assertThatThrownBy(() -> wallet.tryReserve(T0, -1L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("amountMicros");
        }
    }

    @Nested
    @DisplayName("sealing")
    class Sealing {

        @Test
        @DisplayName("a sealed wallet stops spending so its report is final")
        void sealedWalletRefusesToSpend() {
            final SpendAuthority wallet = walletWith(1_000L);
            wallet.tryReserve(T0, 340L);

            assertThat(wallet.sealForRenewal()).isEqualTo(340L);
            assertThat(wallet.isSealed()).isTrue();
            // If it kept spending, the 340 it just reported would be stale and the authority
            // would reclaim money that had already gone out.
            assertThat(wallet.tryReserve(T0, 1L)).isFalse();
        }

        @Test
        @DisplayName("sealing twice reports the same figure")
        void sealingIsIdempotent() {
            final SpendAuthority wallet = walletWith(1_000L);
            wallet.tryReserve(T0, 250L);

            assertThat(wallet.sealForRenewal()).isEqualTo(250L);
            assertThat(wallet.sealForRenewal()).isEqualTo(250L);
        }
    }

    @Nested
    @DisplayName("adopting leases")
    class AdoptingLeases {

        @Test
        @DisplayName("ignores a duplicated or older lease")
        void ignoresStaleLeases() {
            final SpendAuthority wallet = walletWith(1_000L);

            assertThat(wallet.installLease(new Lease(1L, 9_999L, EXPIRY), T0)).isFalse();
            assertThat(wallet.leaseAmountMicros()).isEqualTo(1_000L);
        }

        @Test
        @DisplayName("displacing a live lease parks its final figure instead of losing it")
        void displacementParksTheSpendRecord() {
            final SpendAuthority wallet = walletWith(1_000L);
            wallet.tryReserve(T0, 700L);

            // The prefetch path: the new lease takes over immediately, and the 700 already
            // spent becomes the pending release the next request will settle. Dropping it
            // would let the authority settle low and re-lease spent money.
            assertThat(wallet.installLease(new Lease(2L, 800L, EXPIRY), T0)).isTrue();
            assertThat(wallet.pendingReleaseId()).isEqualTo(1L);
            assertThat(wallet.pendingReleaseSpentMicros()).isEqualTo(700L);
            assertThat(wallet.leaseSpentMicros()).isZero();
            assertThat(wallet.remainingMicros()).isEqualTo(800L);
            assertThat(wallet.tryReserve(T0, 100L)).isTrue();
        }

        @Test
        @DisplayName("a newer grant overwrites the previous pending release")
        void newerGrantOverwritesThePending() {
            final SpendAuthority wallet = walletWith(1_000L);
            wallet.tryReserve(T0, 700L);
            wallet.installLease(new Lease(2L, 800L, EXPIRY), T0);
            wallet.tryReserve(T0, 150L);

            // A strictly newer grant proves the request that named pending lease 1 was
            // processed, so replacing that record with lease 2's figure is safe.
            assertThat(wallet.installLease(new Lease(3L, 900L, EXPIRY), T0)).isTrue();
            assertThat(wallet.pendingReleaseId()).isEqualTo(2L);
            assertThat(wallet.pendingReleaseSpentMicros()).isEqualTo(150L);
        }

        @Test
        @DisplayName("adopting over a sealed or expired lease leaves nothing pending")
        void sealedAdoptionLeavesNothingPending() {
            final SpendAuthority wallet = walletWith(1_000L);
            wallet.tryReserve(T0, 300L);
            wallet.sealForRenewal();

            // The sealed figure travelled with the request that produced this grant, so
            // there is nothing left to park.
            assertThat(wallet.installLease(new Lease(2L, 800L, EXPIRY), T0)).isTrue();
            assertThat(wallet.pendingReleaseId()).isEqualTo(Lease.NONE);
        }

        @Test
        @DisplayName("accepts a new lease once the old one is sealed")
        void acceptsAfterSealing() {
            final SpendAuthority wallet = walletWith(1_000L);
            wallet.tryReserve(T0, 700L);
            wallet.sealForRenewal();

            assertThat(wallet.installLease(new Lease(2L, 800L, EXPIRY), T0)).isTrue();
            assertThat(wallet.leaseSpentMicros()).isZero();
            assertThat(wallet.remainingMicros()).isEqualTo(800L);
            assertThat(wallet.isSealed()).isFalse();
        }

        @Test
        @DisplayName("accepts a new lease once the old one has expired")
        void acceptsAfterExpiry() {
            final SpendAuthority wallet = walletWith(1_000L);
            assertThat(wallet.installLease(new Lease(2L, 800L, EXPIRY * 2), EXPIRY)).isTrue();
            assertThat(wallet.tryReserve(EXPIRY, 800L)).isTrue();
        }
    }

    @Nested
    @DisplayName("renewal timing")
    class RenewalTiming {

        @Test
        @DisplayName("asks for a lease when it has none")
        void needsLeaseWhenEmpty() {
            assertThat(new SpendAuthority(0, 1L).needsLease(T0, 100L, 500L)).isTrue();
        }

        @Test
        @DisplayName("asks once the money runs low")
        void needsLeaseAtLowWater() {
            final SpendAuthority wallet = walletWith(1_000L);
            assertThat(wallet.needsLease(T0, 100L, 500L)).isFalse();

            wallet.tryReserve(T0, 900L);
            assertThat(wallet.needsLease(T0, 100L, 500L)).isTrue();
        }

        @Test
        @DisplayName("asks before the deadline rather than at it, to avoid a gap")
        void needsLeaseAheadOfExpiry() {
            final SpendAuthority wallet = walletWith(1_000L);
            assertThat(wallet.needsLease(EXPIRY - 600L, 100L, 500L)).isFalse();
            assertThat(wallet.needsLease(EXPIRY - 500L, 100L, 500L)).isTrue();
        }

        @Test
        @DisplayName("keeps asking while sealed, so a lost reply is retried")
        void keepsAskingWhileSealed() {
            final SpendAuthority wallet = walletWith(1_000L);
            wallet.sealForRenewal();
            assertThat(wallet.needsLease(T0, 0L, 0L)).isTrue();
        }
    }
}
