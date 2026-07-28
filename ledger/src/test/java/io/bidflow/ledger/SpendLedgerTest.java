package io.bidflow.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.bidflow.budget.Lease;
import io.bidflow.budget.SpendAuthority;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpendLedgerTest {

    @TempDir
    Path temp;

    private static SpendAuthority walletWithLease(long amount) {
        final SpendAuthority wallet = new SpendAuthority(0, 1L);
        wallet.installLease(new Lease(1L, amount, Long.MAX_VALUE), 0L);
        return wallet;
    }

    @Test
    @DisplayName("first charge reserves and returns ACCEPTED")
    void acceptsFirstCharge() throws IOException {
        try (SpendLedger ledger = new SpendLedger(temp, 100)) {
            final SpendAuthority wallet = walletWithLease(1_000L);
            final ChargeResult result = ledger.charge("k1", 7L, 9L, 100L, 1L, wallet);
            assertThat(result.status()).isEqualTo(ChargeStatus.ACCEPTED);
            assertThat(result.amountMicros()).isEqualTo(100L);
            assertThat(wallet.leaseSpentMicros()).isEqualTo(100L);
            assertThat(ledger.committedMicros(0, 1L, 1L)).isEqualTo(100L);
        }
    }

    @Test
    @DisplayName("duplicate key replays without a second reservation")
    void duplicateReplays() throws IOException {
        try (SpendLedger ledger = new SpendLedger(temp, 100)) {
            final SpendAuthority wallet = walletWithLease(1_000L);
            ledger.charge("k1", 7L, 9L, 100L, 1L, wallet);
            final ChargeResult replay = ledger.charge("k1", 7L, 9L, 100L, 2L, wallet);
            assertThat(replay.status()).isEqualTo(ChargeStatus.REPLAYED);
            assertThat(wallet.leaseSpentMicros()).isEqualTo(100L);
            assertThat(ledger.replayedCount()).isEqualTo(1L);
        }
    }

    @Test
    @DisplayName("same key with a different payload is a CONFLICT")
    void conflictOnPayloadMismatch() throws IOException {
        try (SpendLedger ledger = new SpendLedger(temp, 100)) {
            final SpendAuthority wallet = walletWithLease(1_000L);
            ledger.charge("k1", 7L, 9L, 100L, 1L, wallet);
            final ChargeResult conflict = ledger.charge("k1", 7L, 9L, 200L, 2L, wallet);
            assertThat(conflict.status()).isEqualTo(ChargeStatus.CONFLICT);
            assertThat(wallet.leaseSpentMicros()).isEqualTo(100L);
        }
    }

    @Test
    @DisplayName("refusal is durable and replayed without retrying the wallet")
    void refusedIsDurable() throws IOException {
        try (SpendLedger ledger = new SpendLedger(temp, 100)) {
            final SpendAuthority wallet = walletWithLease(50L);
            final ChargeResult refused = ledger.charge("k1", 7L, 9L, 100L, 1L, wallet);
            assertThat(refused.status()).isEqualTo(ChargeStatus.REFUSED);
            assertThat(wallet.leaseSpentMicros()).isZero();

            final ChargeResult replay = ledger.charge("k1", 7L, 9L, 100L, 2L, wallet);
            assertThat(replay.status()).isEqualTo(ChargeStatus.REPLAYED);
            assertThat(replay.amountMicros()).isZero();
        }
    }

    @Test
    @DisplayName("restart from snapshot and WAL preserves outcomes")
    void recoversAcrossRestart() throws IOException {
        final SpendAuthority wallet = walletWithLease(10_000L);
        try (SpendLedger ledger = new SpendLedger(temp, 2)) {
            ledger.charge("a", 1L, 1L, 10L, 1L, wallet);
            ledger.charge("b", 1L, 2L, 20L, 2L, wallet);
            ledger.charge("c", 1L, 3L, 30L, 3L, wallet); // triggers snapshot at 2, then one more WAL
        }
        try (SpendLedger recovered = new SpendLedger(temp, 2)) {
            assertThat(recovered.resultsView()).hasSize(3);
            assertThat(recovered.resultsView().get("a").amountMicros()).isEqualTo(10L);
            assertThat(recovered.resultsView().get("c").amountMicros()).isEqualTo(30L);
            assertThat(recovered.committedMicros(0, 1L, 1L)).isEqualTo(60L);

            final SpendAuthority fresh = walletWithLease(10_000L);
            final ChargeResult replay = recovered.charge("b", 1L, 2L, 20L, 4L, fresh);
            assertThat(replay.status()).isEqualTo(ChargeStatus.REPLAYED);
            assertThat(fresh.leaseSpentMicros()).isZero();
        }
    }

    @Test
    @DisplayName("torn trailing WAL bytes are truncated on recovery")
    void truncatesTornTrailer() throws IOException {
        final SpendAuthority wallet = walletWithLease(1_000L);
        try (SpendLedger ledger = new SpendLedger(temp, 100)) {
            ledger.charge("a", 1L, 1L, 10L, 1L, wallet);
        }
        final Path wal = temp.resolve("spend.wal");
        Files.write(wal, Files.readAllBytes(wal), java.nio.file.StandardOpenOption.APPEND);
        // Append garbage that looks like a partial record.
        Files.write(wal, new byte[] {1, 2, 3, 4, 5}, java.nio.file.StandardOpenOption.APPEND);

        try (SpendLedger recovered = new SpendLedger(temp, 100)) {
            assertThat(recovered.resultsView()).containsOnlyKeys("a");
            final SpendAuthority fresh = walletWithLease(1_000L);
            final ChargeResult next = recovered.charge("b", 1L, 2L, 10L, 1L, fresh);
            assertThat(next.status()).isEqualTo(ChargeStatus.ACCEPTED);
        }
    }

    @Test
    @DisplayName("rejects an empty idempotency key")
    void rejectsEmptyKey() throws IOException {
        try (SpendLedger ledger = new SpendLedger(temp, 100)) {
            final SpendAuthority wallet = walletWithLease(1_000L);
            assertThatThrownBy(() -> ledger.charge("", 1L, 1L, 10L, 1L, wallet))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Property(tries = 50)
    @DisplayName("committed amount equals the sum of unique accepted keys")
    void committedEqualsUniqueAccepted(
            @ForAll @IntRange(min = 1, max = 20) int charges,
            @ForAll @LongRange(min = 1, max = 50) long amount)
            throws IOException {
        final Path dir = Files.createTempDirectory(temp, "prop");
        try (SpendLedger ledger = new SpendLedger(dir, 5)) {
            final SpendAuthority wallet = walletWithLease(1_000_000L);
            final Set<String> unique = new HashSet<>();
            long expected = 0;
            for (int i = 0; i < charges; i++) {
                final String key = "k" + (i % Math.max(1, charges / 2 + 1));
                final ChargeResult result = ledger.charge(key, 1L, i, amount, i, wallet);
                if (result.status() == ChargeStatus.ACCEPTED && unique.add(key)) {
                    expected += amount;
                }
            }
            assertThat(ledger.committedMicros(0, 1L, 1L)).isEqualTo(expected);
        }
    }
}
