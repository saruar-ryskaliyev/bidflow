package io.bidflow.serving;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded, expiring store of auction receipts. A click must present a live token; the server
 * owns the campaign id and cleared price, so clients cannot forge what they are charged.
 */
final class AuctionReceiptStore {

    record SlotReceipt(long campaignId, long priceMicros, long adRank, int qualityBps) {}

    record Receipt(String token, int shardId, long createdAtNanos, long expiresAtNanos, SlotReceipt[] slots) {}

    private final int capacity;
    private final long ttlNanos;
    private final LinkedHashMap<String, Receipt> receipts;

    AuctionReceiptStore(int capacity, long ttlNanos) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive, was " + capacity);
        }
        if (ttlNanos <= 0) {
            throw new IllegalArgumentException("ttlNanos must be positive, was " + ttlNanos);
        }
        this.capacity = capacity;
        this.ttlNanos = ttlNanos;
        this.receipts = new LinkedHashMap<>(capacity, 0.75f, true);
    }

    synchronized Receipt put(String token, int shardId, long nowNanos, SlotReceipt[] slots) {
        expire(nowNanos);
        while (receipts.size() >= capacity) {
            final Map.Entry<String, Receipt> eldest = receipts.entrySet().iterator().next();
            receipts.remove(eldest.getKey());
        }
        final Receipt receipt = new Receipt(
                token, shardId, nowNanos, nowNanos + ttlNanos, Arrays.copyOf(slots, slots.length));
        receipts.put(token, receipt);
        return receipt;
    }

    synchronized Receipt get(String token, long nowNanos) {
        expire(nowNanos);
        final Receipt receipt = receipts.get(token);
        if (receipt == null || nowNanos >= receipt.expiresAtNanos()) {
            receipts.remove(token);
            return null;
        }
        return receipt;
    }

    private void expire(long nowNanos) {
        receipts.entrySet().removeIf(e -> nowNanos >= e.getValue().expiresAtNanos());
    }
}
