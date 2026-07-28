package io.bidflow.ledger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32C;

/**
 * Reads WAL records. A torn final record (short read or bad CRC at EOF) is truncated and
 * ignored; corruption before the tail fails closed.
 */
final class WalReader {

    record ReadResult(List<WalRecord> records, long nextSequence, long validBytes) {}

    static ReadResult read(Path path) throws IOException {
        if (!Files.exists(path) || Files.size(path) == 0) {
            return new ReadResult(List.of(), 1L, 0L);
        }
        final List<WalRecord> records = new ArrayList<>();
        long nextSequence = 1L;
        long validBytes = 0L;
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            final ByteBuffer header = ByteBuffer.allocate(WalWriter.HEADER_LEN).order(ByteOrder.LITTLE_ENDIAN);
            while (true) {
                header.clear();
                final int headerRead = readFully(channel, header);
                if (headerRead == 0) {
                    break;
                }
                if (headerRead < WalWriter.HEADER_LEN) {
                    // Torn header at EOF — truncate.
                    break;
                }
                header.flip();
                final int payloadLen = header.getInt();
                if (payloadLen <= 0 || payloadLen > 1_000_000) {
                    if (validBytes == 0) {
                        throw new IOException("corrupt WAL: impossible payload length " + payloadLen);
                    }
                    // Garbage after a valid prefix — treat as a torn trailer.
                    break;
                }
                final ByteBuffer body = ByteBuffer.allocate(payloadLen + WalWriter.CRC_LEN)
                        .order(ByteOrder.LITTLE_ENDIAN);
                final int bodyRead = readFully(channel, body);
                if (bodyRead < payloadLen + WalWriter.CRC_LEN) {
                    // Torn record at EOF — truncate.
                    break;
                }
                body.flip();
                final byte[] payload = new byte[payloadLen];
                body.get(payload);
                final int storedCrc = body.getInt();
                final CRC32C crc = new CRC32C();
                crc.update(payload);
                if ((int) crc.getValue() != storedCrc) {
                    // Bad CRC at the tail is treated as a tear; earlier that would mean the
                    // previous record's validBytes already committed, so this is EOF damage.
                    if (records.isEmpty() && validBytes == 0) {
                        throw new IOException("corrupt WAL: CRC mismatch at start of file");
                    }
                    break;
                }
                final ByteBuffer payloadBuf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
                final int version = payloadBuf.getInt();
                if (version != WalWriter.VERSION) {
                    throw new IOException("unsupported WAL version " + version);
                }
                final long sequence = payloadBuf.getLong();
                final int keyLen = payloadBuf.getInt();
                final byte[] keyBytes = new byte[keyLen];
                payloadBuf.get(keyBytes);
                final String key = new String(keyBytes, StandardCharsets.UTF_8);
                final long campaignId = payloadBuf.getLong();
                final long auctionId = payloadBuf.getLong();
                final int shardId = payloadBuf.getInt();
                final long incarnation = payloadBuf.getLong();
                final long leaseId = payloadBuf.getLong();
                final long amountMicros = payloadBuf.getLong();
                final int statusOrdinal = payloadBuf.getInt();
                final ChargeStatus status = ChargeStatus.values()[statusOrdinal];
                records.add(new WalRecord(
                        sequence, key, campaignId, auctionId, shardId, incarnation, leaseId,
                        amountMicros, status));
                nextSequence = sequence + 1;
                validBytes += WalWriter.HEADER_LEN + payloadLen + WalWriter.CRC_LEN;
            }
        }
        return new ReadResult(records, nextSequence, validBytes);
    }

    /** Truncates the WAL file to {@code validBytes}, discarding a torn trailer. */
    static void truncateTo(Path path, long validBytes) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.truncate(validBytes);
            channel.force(true);
        }
    }

    private static int readFully(FileChannel channel, ByteBuffer buf) throws IOException {
        int total = 0;
        while (buf.hasRemaining()) {
            final int n = channel.read(buf);
            if (n < 0) {
                return total;
            }
            total += n;
        }
        return total;
    }

    private WalReader() {}
}
