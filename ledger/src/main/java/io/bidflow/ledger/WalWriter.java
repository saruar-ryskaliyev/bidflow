package io.bidflow.ledger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32C;

/**
 * Appends length-prefixed, CRC32C-protected WAL records and forces them to disk.
 *
 * <p>Record layout (little-endian):
 * <pre>
 *   int32  payloadLength
 *   int32  version (=1)
 *   int64  sequence
 *   int32  keyLength
 *   bytes  key UTF-8
 *   int64  campaignId
 *   int64  auctionId
 *   int32  shardId
 *   int64  incarnation
 *   int64  leaseId
 *   int64  amountMicros
 *   int32  statusOrdinal
 *   int32  crc32c of everything after payloadLength through statusOrdinal
 * </pre>
 */
final class WalWriter implements AutoCloseable {

    static final int VERSION = 1;
    static final int HEADER_LEN = 4; // payload length
    static final int CRC_LEN = 4;

    private final FileChannel channel;
    private long nextSequence;

    WalWriter(Path path, long nextSequence) throws IOException {
        this.channel = FileChannel.open(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);
        this.nextSequence = nextSequence;
    }

    long nextSequence() {
        return nextSequence;
    }

    WalRecord append(
            String key,
            long campaignId,
            long auctionId,
            int shardId,
            long incarnation,
            long leaseId,
            long amountMicros,
            ChargeStatus status)
            throws IOException {
        final long sequence = nextSequence++;
        final WalRecord record = new WalRecord(
                sequence, key, campaignId, auctionId, shardId, incarnation, leaseId, amountMicros, status);
        final byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        final int payloadLen = 4 + 8 + 4 + keyBytes.length + 8 + 8 + 4 + 8 + 8 + 8 + 4;
        final ByteBuffer buf = ByteBuffer.allocate(HEADER_LEN + payloadLen + CRC_LEN).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(payloadLen);
        final int payloadStart = buf.position();
        buf.putInt(VERSION);
        buf.putLong(sequence);
        buf.putInt(keyBytes.length);
        buf.put(keyBytes);
        buf.putLong(campaignId);
        buf.putLong(auctionId);
        buf.putInt(shardId);
        buf.putLong(incarnation);
        buf.putLong(leaseId);
        buf.putLong(amountMicros);
        buf.putInt(status.ordinal());
        final CRC32C crc = new CRC32C();
        crc.update(buf.array(), payloadStart, payloadLen);
        buf.putInt((int) crc.getValue());
        buf.flip();
        while (buf.hasRemaining()) {
            channel.write(buf);
        }
        return record;
    }

    void force() throws IOException {
        channel.force(true);
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
