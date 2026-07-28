package io.bidflow.ledger;

import io.bidflow.budget.SpendAuthority;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Single-writer idempotent spend ledger.
 *
 * <p>Ordering on every charge: look up the key, reserve from the local wallet on a first
 * sighting, append and force the outcome, then acknowledge. If persistence fails after a
 * reservation the call fails and the reserved authority is stranded rather than risking a
 * double bill; recovery reconciles committed per-lease totals from the durable log.
 *
 * <p><b>Not thread-safe.</b> Confine one instance to one shard writer thread.
 */
public final class SpendLedger implements AutoCloseable {

    private static final String WAL_NAME = "spend.wal";
    private static final String SNAPSHOT_NAME = "spend.snapshot";

    private final Path directory;
    private final int snapshotEvery;
    private final Map<String, ChargeResult> results;
    private final Map<LedgerSnapshot.LeaseKey, Long> leaseTotals;
    private WalWriter writer;
    private long chargesSinceSnapshot;
    private long acceptedCount;
    private long replayedCount;
    private long refusedCount;
    private long conflictCount;

    /**
     * @param directory durable directory for the WAL and snapshot
     * @param snapshotEvery take a snapshot after this many new durable charges; must be positive
     */
    public SpendLedger(Path directory, int snapshotEvery) throws IOException {
        if (snapshotEvery <= 0) {
            throw new IllegalArgumentException("snapshotEvery must be positive, was " + snapshotEvery);
        }
        this.directory = Objects.requireNonNull(directory, "directory");
        this.snapshotEvery = snapshotEvery;
        Files.createDirectories(directory);
        final LedgerSnapshot.State snap = LedgerSnapshot.read(snapshotPath());
        this.results = new HashMap<>(snap.results());
        this.leaseTotals = new HashMap<>(snap.leaseTotals());
        final Path wal = walPath();
        final WalReader.ReadResult walResult = WalReader.read(wal);
        if (Files.exists(wal) && walResult.validBytes() < Files.size(wal)) {
            WalReader.truncateTo(wal, walResult.validBytes());
        }
        for (WalRecord record : walResult.records()) {
            if (record.sequence() <= snap.lastSequence()) {
                continue;
            }
            apply(record);
        }
        this.writer = new WalWriter(wal, Math.max(walResult.nextSequence(), snap.lastSequence() + 1));
    }

    /**
     * Charges {@code amountMicros} against {@code wallet} exactly once per idempotency key.
     *
     * @throws IOException if the outcome cannot be forced to disk after a reservation
     */
    public ChargeResult charge(
            String idempotencyKey,
            long campaignId,
            long auctionId,
            long amountMicros,
            long nowNanos,
            SpendAuthority wallet)
            throws IOException {
        if (idempotencyKey == null || idempotencyKey.isEmpty()) {
            throw new IllegalArgumentException("idempotencyKey must be non-empty");
        }
        if (amountMicros < 0) {
            throw new IllegalArgumentException("amountMicros must not be negative, was " + amountMicros);
        }
        if (wallet == null) {
            throw new IllegalArgumentException("wallet must not be null");
        }

        final ChargeResult prior = results.get(idempotencyKey);
        if (prior != null) {
            if (prior.campaignId() != campaignId
                    || prior.auctionId() != auctionId
                    || prior.amountMicros() != amountMicros) {
                conflictCount++;
                return new ChargeResult(ChargeStatus.CONFLICT, 0L, campaignId, auctionId);
            }
            replayedCount++;
            final long replayAmount = prior.status() == ChargeStatus.ACCEPTED ? prior.amountMicros() : 0L;
            return new ChargeResult(ChargeStatus.REPLAYED, replayAmount, campaignId, auctionId);
        }

        final boolean reserved = amountMicros == 0 || wallet.tryReserve(nowNanos, amountMicros);
        final ChargeStatus status = reserved ? ChargeStatus.ACCEPTED : ChargeStatus.REFUSED;
        final long leaseId = reserved ? wallet.leaseId() : 0L;

        try {
            final WalRecord record = writer.append(
                    idempotencyKey,
                    campaignId,
                    auctionId,
                    wallet.shardId(),
                    wallet.incarnation(),
                    leaseId,
                    amountMicros,
                    status);
            writer.force();
            apply(record);
            chargesSinceSnapshot++;
            if (status == ChargeStatus.ACCEPTED) {
                acceptedCount++;
            } else {
                refusedCount++;
            }
            if (chargesSinceSnapshot >= snapshotEvery) {
                snapshot();
            }
            return new ChargeResult(status, reserved ? amountMicros : 0L, campaignId, auctionId);
        } catch (IOException e) {
            // Reserved authority is stranded: failing open would risk a second charge on retry
            // before the first reservation was durable.
            throw e;
        }
    }

    /** Forces a snapshot and truncates the WAL to start after the snapshotted sequence. */
    public void snapshot() throws IOException {
        final long lastSequence = writer.nextSequence() - 1;
        if (lastSequence <= 0) {
            return;
        }
        LedgerSnapshot.write(
                snapshotPath(),
                new LedgerSnapshot.State(lastSequence, new HashMap<>(results), new HashMap<>(leaseTotals)));
        writer.close();
        Files.deleteIfExists(walPath());
        writer = new WalWriter(walPath(), lastSequence + 1);
        chargesSinceSnapshot = 0;
    }

    /** Committed spend for a lease, recovered across restarts. */
    public long committedMicros(int shardId, long incarnation, long leaseId) {
        return leaseTotals.getOrDefault(new LedgerSnapshot.LeaseKey(shardId, incarnation, leaseId), 0L);
    }

    public Map<String, ChargeResult> resultsView() {
        return Collections.unmodifiableMap(results);
    }

    public long acceptedCount() {
        return acceptedCount;
    }

    public long replayedCount() {
        return replayedCount;
    }

    public long refusedCount() {
        return refusedCount;
    }

    public long conflictCount() {
        return conflictCount;
    }

    public Path directory() {
        return directory;
    }

    private void apply(WalRecord record) {
        // Store the requested amount so a replay can fingerprint the payload, including refusals.
        results.put(
                record.idempotencyKey(),
                new ChargeResult(
                        record.status(), record.amountMicros(), record.campaignId(), record.auctionId()));
        if (record.status() == ChargeStatus.ACCEPTED && record.leaseId() > 0) {
            final LedgerSnapshot.LeaseKey key =
                    new LedgerSnapshot.LeaseKey(record.shardId(), record.incarnation(), record.leaseId());
            leaseTotals.merge(key, record.amountMicros(), Long::sum);
        }
    }

    private Path walPath() {
        return directory.resolve(WAL_NAME);
    }

    private Path snapshotPath() {
        return directory.resolve(SNAPSHOT_NAME);
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }
}
