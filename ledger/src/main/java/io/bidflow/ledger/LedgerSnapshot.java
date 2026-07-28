package io.bidflow.ledger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32C;

/**
 * Checksummed snapshot of the idempotency map and per-lease committed totals.
 *
 * <p>Written to a temporary file, forced, then atomically renamed over the live snapshot.
 * WAL rotation is safe only after this rename completes.
 */
final class LedgerSnapshot {

    static final int VERSION = 1;

    record State(
            long lastSequence,
            Map<String, ChargeResult> results,
            Map<LeaseKey, Long> leaseTotals) {}

    record LeaseKey(int shardId, long incarnation, long leaseId) {}

    static void write(Path snapshotPath, State state) throws IOException {
        final Path tmp = snapshotPath.resolveSibling(snapshotPath.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        try (FileChannel channel = FileChannel.open(
                tmp,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            final byte[] payload = encode(state);
            final ByteBuffer buf = ByteBuffer.allocate(4 + payload.length + 4).order(ByteOrder.LITTLE_ENDIAN);
            buf.putInt(payload.length);
            buf.put(payload);
            final CRC32C crc = new CRC32C();
            crc.update(payload);
            buf.putInt((int) crc.getValue());
            buf.flip();
            while (buf.hasRemaining()) {
                channel.write(buf);
            }
            channel.force(true);
        }
        Files.move(tmp, snapshotPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static State read(Path snapshotPath) throws IOException {
        if (!Files.exists(snapshotPath) || Files.size(snapshotPath) == 0) {
            return new State(0L, new HashMap<>(), new HashMap<>());
        }
        final byte[] file = Files.readAllBytes(snapshotPath);
        final ByteBuffer buf = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        final int payloadLen = buf.getInt();
        if (payloadLen <= 0 || payloadLen > file.length - 8) {
            throw new IOException("corrupt snapshot: bad payload length");
        }
        final byte[] payload = new byte[payloadLen];
        buf.get(payload);
        final int storedCrc = buf.getInt();
        final CRC32C crc = new CRC32C();
        crc.update(payload);
        if ((int) crc.getValue() != storedCrc) {
            throw new IOException("corrupt snapshot: CRC mismatch");
        }
        return decode(payload);
    }

    private static byte[] encode(State state) {
        int size = 4 + 8 + 4; // version, lastSequence, resultCount
        for (Map.Entry<String, ChargeResult> e : state.results().entrySet()) {
            size += 4 + e.getKey().getBytes(StandardCharsets.UTF_8).length + 8 + 8 + 8 + 4;
        }
        size += 4; // lease count
        size += state.leaseTotals().size() * (4 + 8 + 8 + 8);
        final ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(VERSION);
        buf.putLong(state.lastSequence());
        buf.putInt(state.results().size());
        for (Map.Entry<String, ChargeResult> e : state.results().entrySet()) {
            final byte[] key = e.getKey().getBytes(StandardCharsets.UTF_8);
            final ChargeResult r = e.getValue();
            buf.putInt(key.length);
            buf.put(key);
            buf.putLong(r.campaignId());
            buf.putLong(r.auctionId());
            buf.putLong(r.amountMicros());
            buf.putInt(r.status().ordinal());
        }
        buf.putInt(state.leaseTotals().size());
        for (Map.Entry<LeaseKey, Long> e : state.leaseTotals().entrySet()) {
            final LeaseKey k = e.getKey();
            buf.putInt(k.shardId());
            buf.putLong(k.incarnation());
            buf.putLong(k.leaseId());
            buf.putLong(e.getValue());
        }
        return buf.array();
    }

    private static State decode(byte[] payload) throws IOException {
        final ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        final int version = buf.getInt();
        if (version != VERSION) {
            throw new IOException("unsupported snapshot version " + version);
        }
        final long lastSequence = buf.getLong();
        final int resultCount = buf.getInt();
        final Map<String, ChargeResult> results = new HashMap<>(resultCount);
        for (int i = 0; i < resultCount; i++) {
            final int keyLen = buf.getInt();
            final byte[] keyBytes = new byte[keyLen];
            buf.get(keyBytes);
            final long campaignId = buf.getLong();
            final long auctionId = buf.getLong();
            final long amount = buf.getLong();
            final ChargeStatus status = ChargeStatus.values()[buf.getInt()];
            results.put(
                    new String(keyBytes, StandardCharsets.UTF_8),
                    new ChargeResult(status, amount, campaignId, auctionId));
        }
        final int leaseCount = buf.getInt();
        final Map<LeaseKey, Long> leases = new HashMap<>(leaseCount);
        for (int i = 0; i < leaseCount; i++) {
            final LeaseKey key = new LeaseKey(buf.getInt(), buf.getLong(), buf.getLong());
            leases.put(key, buf.getLong());
        }
        return new State(lastSequence, results, leases);
    }

    private LedgerSnapshot() {}
}
