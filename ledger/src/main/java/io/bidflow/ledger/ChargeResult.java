package io.bidflow.ledger;

/**
 * Immutable result of a {@link SpendLedger#charge charge}.
 *
 * @param status whether the charge was new, replayed, refused, or a conflict
 * @param amountMicros the amount associated with the key (zero when refused/conflicted)
 * @param campaignId campaign that was (or would have been) charged
 * @param auctionId auction the click belonged to
 */
public record ChargeResult(ChargeStatus status, long amountMicros, long campaignId, long auctionId) {

    public ChargeResult {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if (amountMicros < 0) {
            throw new IllegalArgumentException("amountMicros must not be negative, was " + amountMicros);
        }
    }

    public boolean acceptedOrReplayed() {
        return status == ChargeStatus.ACCEPTED || status == ChargeStatus.REPLAYED;
    }
}
