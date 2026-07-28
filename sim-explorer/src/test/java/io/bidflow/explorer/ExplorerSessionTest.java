package io.bidflow.explorer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.bidflow.budget.sim.BudgetCluster;
import io.bidflow.budget.sim.ClusterSnapshot;
import io.bidflow.budget.sim.ShardSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExplorerSessionTest {

    private static final long MILLIS = 1_000_000L;

    private static ExplorerSession session(
            long seed, int shardCount, long budgetMicros, String networkPreset, boolean autoTraffic) {
        return new ExplorerSession(
                seed, shardCount, budgetMicros, networkPreset, null, null, autoTraffic, null);
    }

    @Test
    @DisplayName("reset creates the requested shard count")
    void resetCreatesRequestedShardCount() {
        final ExplorerSession explorer = session(1L, 5, 1_000_000L, "perfect", false);

        assertThat(explorer.shardCount()).isEqualTo(5);
        assertThat(explorer.snapshot().snapshot().shards()).hasSize(5);
    }

    @Test
    @DisplayName("advance with auto traffic moves spend and served counters")
    void advanceWithAutoTrafficMovesSpend() {
        final ExplorerSession explorer = session(2L, 2, 5_000_000L, "perfect", true);
        final ClusterSnapshot before = explorer.snapshot().snapshot();

        explorer.advance(50 * MILLIS);

        final ClusterSnapshot after = explorer.snapshot().snapshot();
        assertThat(after.actualSpendMicros()).isGreaterThan(before.actualSpendMicros());
        assertThat(after.servedRequests()).isGreaterThan(before.servedRequests());
    }

    @Test
    @DisplayName("manual search with traffic off runs injectSearch")
    void manualSearchWithTrafficOff() {
        final ExplorerSession explorer = session(3L, 1, 5_000_000L, "perfect", false);
        final ClusterSnapshot before = explorer.snapshot().snapshot();

        explorer.apply(new ExplorerCommand.Search(0));

        final ClusterSnapshot after = explorer.snapshot().snapshot();
        assertThat(after.servedRequests() + after.refusedRequests() + after.lostAuctions())
                .isGreaterThan(before.servedRequests() + before.refusedRequests() + before.lostAuctions());
    }

    @Test
    @DisplayName("crash leaves shard not alive and restart bumps incarnation")
    void crashAndRestartShard() {
        final ExplorerSession explorer = session(4L, 2, 5_000_000L, "perfect", false);
        final long incarnationBefore = explorer.snapshot().snapshot().shards().get(1).incarnation();

        explorer.apply(new ExplorerCommand.CrashShard(1));
        assertThat(explorer.snapshot().snapshot().shards().get(1).alive()).isFalse();

        explorer.apply(new ExplorerCommand.RestartShard(1));
        final ShardSnapshot restarted = explorer.snapshot().snapshot().shards().get(1);
        assertThat(restarted.alive()).isTrue();
        assertThat(restarted.incarnation()).isGreaterThan(incarnationBefore);
    }

    @Test
    @DisplayName("partition between authority and shard blocks then heal restores")
    void partitionAndHealAuthorityToShard() {
        final ExplorerSession explorer = session(5L, 1, 5_000_000L, "perfect", true);
        final int authority = BudgetCluster.AUTHORITY_NODE;
        final int shardNode = ExplorerSession.shardToNode(0);

        explorer.apply(new ExplorerCommand.Partition(authority, shardNode));
        explorer.advance(20 * MILLIS);

        final ClusterSnapshot partitioned = explorer.snapshot().snapshot();
        assertThat(partitioned.blockedLinks()).isNotEmpty();
        assertThat(partitioned.networkPartitioned()).isGreaterThan(0L);

        explorer.apply(new ExplorerCommand.Heal(authority, shardNode));
        explorer.advance(20 * MILLIS);

        final ClusterSnapshot healed = explorer.snapshot().snapshot();
        assertThat(healed.blockedLinks()).isEmpty();
    }

    @Test
    @DisplayName("snapshot money fields are consistent")
    void snapshotMoneyFieldsAreConsistent() {
        final ExplorerSession explorer = session(6L, 3, 2_000_000L, "perfect", true);
        explorer.advance(100 * MILLIS);

        final ClusterSnapshot snapshot = explorer.snapshot().snapshot();
        assertThat(snapshot.overspendMicros())
                .isEqualTo(Math.max(0L, snapshot.actualSpendMicros() - snapshot.budgetMicros()));
        assertThat(snapshot.spendableRemainderMicros())
                .isEqualTo(Math.max(0L, snapshot.budgetMicros() - snapshot.actualSpendMicros()));
    }

    @Test
    @DisplayName("event journal truncates and reports when afterSeq is too old")
    void eventJournalTruncatesOldCursors() {
        final ExplorerEventJournal journal = new ExplorerEventJournal(3);
        journal.append(eventAt(1L, "one"));
        journal.append(eventAt(2L, "two"));
        journal.append(eventAt(3L, "three"));

        ExplorerEventPage page = journal.eventsAfter(0, 10);
        assertThat(page.events()).hasSize(3);
        assertThat(page.truncated()).isFalse();

        journal.append(eventAt(4L, "four"));
        page = journal.eventsAfter(1, 10);
        assertThat(page.truncated()).isTrue();
        assertThat(page.events()).extracting(ExplorerEvent::seq).containsExactly(2L, 3L, 4L);
    }

    @Test
    @DisplayName("deterministic replay reproduces trace digest and spend")
    void deterministicReplay() {
        final ExplorerSession original = session(7L, 2, 3_000_000L, "perfect", true);
        original.apply(new ExplorerCommand.Advance(30 * MILLIS));
        original.apply(new ExplorerCommand.SetTraffic(false));
        original.apply(new ExplorerCommand.Search(0));
        original.apply(new ExplorerCommand.StepN(25));
        original.apply(new ExplorerCommand.CrashShard(1));
        original.apply(new ExplorerCommand.RestartShard(1));
        original.apply(new ExplorerCommand.Advance(10 * MILLIS));

        final String digest = original.traceDigest();
        final long spend = original.snapshot().snapshot().actualSpendMicros();
        final long eventsFired = original.snapshot().snapshot().eventsFired();

        final ExplorerSession replayed = original.replayed();
        assertThat(replayed.traceDigest()).isEqualTo(digest);
        assertThat(replayed.snapshot().snapshot().actualSpendMicros()).isEqualTo(spend);
        assertThat(replayed.snapshot().snapshot().eventsFired()).isEqualTo(eventsFired);
        assertThat(replayed.commands()).isEqualTo(original.commands());
    }

    @Test
    @DisplayName("apply replay mutates the session to match replayed()")
    void applyReplayMatchesReplayed() {
        final ExplorerSession original = session(8L, 1, 1_000_000L, "perfect", true);
        original.apply(new ExplorerCommand.Advance(15 * MILLIS));
        original.apply(new ExplorerCommand.SetTraffic(false));

        final ExplorerSession expected = original.replayed();
        original.apply(new ExplorerCommand.Replay());

        assertThat(original.traceDigest()).isEqualTo(expected.traceDigest());
        assertThat(original.snapshot().snapshot().actualSpendMicros())
                .isEqualTo(expected.snapshot().snapshot().actualSpendMicros());
    }

    @Test
    @DisplayName("invalid shard count is rejected")
    void invalidShardCountRejected() {
        assertThatThrownBy(() -> session(1L, 0, 1_000L, "perfect", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> session(1L, 33, 1_000L, "perfect", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ExplorerEvent eventAt(long timeNanos, String detail) {
        return new ExplorerEvent(0L, timeNanos, "CONTROL", -1, -1, "", false, detail);
    }
}
