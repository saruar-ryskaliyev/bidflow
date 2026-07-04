package io.bidflow.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BudgetAuthorityTest {

    @Test
    @DisplayName("grants what is asked for while budget remains")
    void grantsWhileBudgetRemains() {
        final BudgetAuthority bank = new BudgetAuthority(1_000L, 2);

        assertThat(bank.requestAuthority(0, 1L, 300L)).isEqualTo(300L);
        assertThat(bank.requestAuthority(0, 1L, 200L)).isEqualTo(500L);
        assertThat(bank.totalGrantedMicros()).isEqualTo(500L);
        assertThat(bank.unallocatedMicros()).isEqualTo(500L);
    }

    @Test
    @DisplayName("returns a running total, not the increment")
    void returnsCumulativeAuthority() {
        final BudgetAuthority bank = new BudgetAuthority(1_000L, 1);

        assertThat(bank.requestAuthority(0, 1L, 100L)).isEqualTo(100L);
        assertThat(bank.requestAuthority(0, 1L, 100L)).isEqualTo(200L);
        assertThat(bank.requestAuthority(0, 1L, 100L)).isEqualTo(300L);
    }

    @Test
    @DisplayName("never hands out more than the budget, however hard it is asked")
    void neverGrantsBeyondTheBudget() {
        final BudgetAuthority bank = new BudgetAuthority(1_000L, 4);

        for (int shard = 0; shard < 4; shard++) {
            bank.requestAuthority(shard, 1L, 800L);
        }
        for (int shard = 0; shard < 4; shard++) {
            bank.requestAuthority(shard, 1L, 800L);
        }

        // This is the single invariant the whole safety argument rests on.
        assertThat(bank.totalGrantedMicros()).isEqualTo(1_000L);
        assertThat(bank.unallocatedMicros()).isZero();
        assertThat(bank.grantsExhausted()).isPositive();
    }

    @Test
    @DisplayName("hands out the last partial amount rather than refusing outright")
    void grantsThePartialRemainder() {
        final BudgetAuthority bank = new BudgetAuthority(1_000L, 2);
        bank.requestAuthority(0, 1L, 900L);

        assertThat(bank.requestAuthority(1, 1L, 500L)).isEqualTo(100L);
        assertThat(bank.totalGrantedMicros()).isEqualTo(1_000L);
    }

    @Test
    @DisplayName("a restarted shard begins again from zero")
    void restartedShardStartsFromZero() {
        final BudgetAuthority bank = new BudgetAuthority(1_000L, 1);
        bank.requestAuthority(0, 1L, 400L);

        // The restarted process has no idea what its predecessor spent, so it must not be
        // told it already holds 400 — that would let it spend the same money twice.
        assertThat(bank.requestAuthority(0, 2L, 100L)).isEqualTo(100L);
        assertThat(bank.grantedMicros(0)).isEqualTo(500L);
        assertThat(bank.totalGrantedMicros()).isEqualTo(500L);
        assertThat(bank.restartsObserved()).isEqualTo(1L);
    }

    @Test
    @DisplayName("a restart still counts the predecessor's authority against the budget")
    void restartDoesNotRecoverThePredecessorsAuthority() {
        final BudgetAuthority bank = new BudgetAuthority(1_000L, 1);
        bank.requestAuthority(0, 1L, 1_000L);

        // Nothing is left, because the dead process might have spent all of it.
        assertThat(bank.requestAuthority(0, 2L, 500L)).isZero();
        assertThat(bank.unallocatedMicros()).isZero();
    }

    @Test
    @DisplayName("refuses a request from a process that has already been replaced")
    void supersededRequestIsRefused() {
        final BudgetAuthority bank = new BudgetAuthority(1_000L, 1);
        bank.requestAuthority(0, 2L, 100L);

        assertThat(bank.requestAuthority(0, 1L, 100L)).isEqualTo(BudgetAuthority.SUPERSEDED);
        assertThat(bank.grantsSuperseded()).isEqualTo(1L);
        assertThat(bank.totalGrantedMicros()).isEqualTo(100L);
    }

    @Test
    @DisplayName("keeps the highest spend report and ignores stale ones")
    void reportsAreMonotonic() {
        final BudgetAuthority bank = new BudgetAuthority(1_000L, 1);
        bank.requestAuthority(0, 1L, 500L);

        bank.recordReport(0, 1L, 300L);
        bank.recordReport(0, 1L, 120L);
        bank.recordReport(0, 1L, 300L);

        // Duplicates and reordering are ordinary on a network, so the report path has to be
        // immune to both without keeping a table of what it has already seen.
        assertThat(bank.reportedSpentMicros(0)).isEqualTo(300L);
    }

    @Test
    @DisplayName("ignores a report from the wrong process")
    void reportFromAnotherIncarnationIsIgnored() {
        final BudgetAuthority bank = new BudgetAuthority(1_000L, 1);
        bank.requestAuthority(0, 2L, 500L);

        bank.recordReport(0, 1L, 400L);
        assertThat(bank.reportedSpentMicros(0)).isZero();

        bank.recordReport(0, 2L, 400L);
        assertThat(bank.reportedSpentMicros(0)).isEqualTo(400L);
    }

    @Test
    @DisplayName("accumulates reported spend across restarts")
    void reportedSpendAccumulatesAcrossRestarts() {
        final BudgetAuthority bank = new BudgetAuthority(10_000L, 1);
        bank.requestAuthority(0, 1L, 1_000L);
        bank.recordReport(0, 1L, 700L);

        bank.requestAuthority(0, 2L, 1_000L);
        bank.recordReport(0, 2L, 250L);

        assertThat(bank.reportedSpentMicros(0)).isEqualTo(950L);
    }

    @Test
    @DisplayName("accounts for the money a crash left behind")
    void tracksBudgetStrandedByRestarts() {
        final BudgetAuthority bank = new BudgetAuthority(10_000L, 1);
        bank.requestAuthority(0, 1L, 1_000L);
        bank.recordReport(0, 1L, 400L);

        bank.requestAuthority(0, 2L, 1_000L);

        // 1,000 granted, 400 known spent, so 600 is unrecoverable: it may have been spent
        // without ever being reported, and assuming otherwise is how overspend happens.
        assertThat(bank.strandedByRestartsMicros()).isEqualTo(600L);
    }

    @Test
    @DisplayName("a zero budget grants nothing")
    void zeroBudgetGrantsNothing() {
        final BudgetAuthority bank = new BudgetAuthority(0L, 1);
        assertThat(bank.requestAuthority(0, 1L, 100L)).isZero();
    }

    @Test
    @DisplayName("rejects an unknown shard")
    void rejectsUnknownShard() {
        final BudgetAuthority bank = new BudgetAuthority(1_000L, 2);
        assertThatThrownBy(() -> bank.requestAuthority(2, 1L, 100L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shardId");
    }
}
