package io.bidflow.ledger;

/**
 * One durable charge record.
 *
 * @param sequence monotonically increasing WAL sequence
 * @param idempotencyKey client-supplied unique key for the click
 * @param campaignId campaign charged
 * @param auctionId auction the click belonged to
 * @param shardId wallet shard
 * @param incarnation wallet incarnation at charge time
 * @param leaseId lease the charge reserved against, or 0 when refused without a lease
 * @param amountMicros amount reserved (0 when refused)
 * @param status durable outcome; never {@link ChargeStatus#REPLAYED} or {@link ChargeStatus#CONFLICT}
 *     — those are derived at read time
 */
record WalRecord(
        long sequence,
        String idempotencyKey,
        long campaignId,
        long auctionId,
        int shardId,
        long incarnation,
        long leaseId,
        long amountMicros,
        ChargeStatus status) {

    WalRecord {
        if (sequence <= 0) {
            throw new IllegalArgumentException("sequence must be positive, was " + sequence);
        }
        if (idempotencyKey == null || idempotencyKey.isEmpty()) {
            throw new IllegalArgumentException("idempotencyKey must be non-empty");
        }
        if (amountMicros < 0) {
            throw new IllegalArgumentException("amountMicros must not be negative, was " + amountMicros);
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if (status == ChargeStatus.REPLAYED || status == ChargeStatus.CONFLICT) {
            throw new IllegalArgumentException("WAL stores only ACCEPTED or REFUSED, was " + status);
        }
    }
}
