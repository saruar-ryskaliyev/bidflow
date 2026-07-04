package io.bidflow.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SpendAuthorityTest {

    @Test
    @DisplayName("a fresh wallet can spend nothing")
    void startsEmpty() {
        final SpendAuthority wallet = new SpendAuthority(0, 1L);
        assertThat(wallet.remainingMicros()).isZero();
        assertThat(wallet.tryReserve(1L)).isFalse();
    }

    @Test
    @DisplayName("spends up to its authority and then refuses")
    void spendsUpToItsAuthority() {
        final SpendAuthority wallet = new SpendAuthority(0, 1L);
        wallet.applyGrant(1L, 1_000L);

        assertThat(wallet.tryReserve(600L)).isTrue();
        assertThat(wallet.tryReserve(400L)).isTrue();
        assertThat(wallet.tryReserve(1L)).isFalse();
        assertThat(wallet.spentMicros()).isEqualTo(1_000L);
        assertThat(wallet.remainingMicros()).isZero();
    }

    @Test
    @DisplayName("a refused spend commits nothing")
    void refusedSpendIsNotPartiallyApplied() {
        final SpendAuthority wallet = new SpendAuthority(0, 1L);
        wallet.applyGrant(1L, 100L);

        assertThat(wallet.tryReserve(101L)).isFalse();
        assertThat(wallet.spentMicros()).isZero();
        assertThat(wallet.tryReserve(100L)).isTrue();
    }

    @Test
    @DisplayName("an enormous request cannot overflow its way past the check")
    void hugeRequestDoesNotOverflow() {
        final SpendAuthority wallet = new SpendAuthority(0, 1L);
        wallet.applyGrant(1L, 100L);

        // Comparing on the remaining side rather than adding to the spent side is what makes
        // this false instead of a wraparound that quietly passes.
        assertThat(wallet.tryReserve(Long.MAX_VALUE)).isFalse();
        assertThat(wallet.spentMicros()).isZero();
    }

    @Test
    @DisplayName("applying the same grant twice grants nothing extra")
    void duplicateGrantIsHarmless() {
        final SpendAuthority wallet = new SpendAuthority(0, 1L);

        assertThat(wallet.applyGrant(1L, 500L)).isTrue();
        assertThat(wallet.applyGrant(1L, 500L)).isFalse();

        // The whole reason grants carry a total instead of an increment: a duplicated
        // "here is 500 more" would have produced 1000.
        assertThat(wallet.authorityMicros()).isEqualTo(500L);
    }

    @Test
    @DisplayName("a grant that arrives late cannot take authority back")
    void staleGrantCannotReduceAuthority() {
        final SpendAuthority wallet = new SpendAuthority(0, 1L);
        wallet.applyGrant(1L, 900L);

        assertThat(wallet.applyGrant(1L, 400L)).isFalse();
        assertThat(wallet.authorityMicros()).isEqualTo(900L);
    }

    @Test
    @DisplayName("a grant meant for a previous process is ignored")
    void grantForAnotherIncarnationIsIgnored() {
        final SpendAuthority restarted = new SpendAuthority(0, 2L);

        assertThat(restarted.applyGrant(1L, 5_000L)).isFalse();
        assertThat(restarted.authorityMicros()).isZero();
        assertThat(restarted.tryReserve(1L)).isFalse();

        assertThat(restarted.applyGrant(2L, 5_000L)).isTrue();
        assertThat(restarted.tryReserve(5_000L)).isTrue();
    }

    @Test
    @DisplayName("reports that it needs topping up once it falls to the threshold")
    void signalsWhenItNeedsMoney() {
        final SpendAuthority wallet = new SpendAuthority(0, 1L);
        wallet.applyGrant(1L, 1_000L);

        assertThat(wallet.needsTopUp(100L)).isFalse();
        assertThat(wallet.tryReserve(900L)).isTrue();
        assertThat(wallet.needsTopUp(100L)).isTrue();
    }

    @Test
    @DisplayName("rejects a negative spend")
    void rejectsNegativeSpend() {
        final SpendAuthority wallet = new SpendAuthority(0, 1L);
        assertThatThrownBy(() -> wallet.tryReserve(-1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amountMicros");
    }

    @Test
    @DisplayName("a zero-cost spend always succeeds and changes nothing")
    void zeroSpendIsFree() {
        final SpendAuthority wallet = new SpendAuthority(0, 1L);
        assertThat(wallet.tryReserve(0L)).isTrue();
        assertThat(wallet.spentMicros()).isZero();
    }
}
