package io.bidflow.ledger;

/**
 * Outcome of a ledger {@link SpendLedger#charge charge}.
 *
 * <ul>
 *   <li>{@link #ACCEPTED} — first time this key was seen; the wallet was reserved and the
 *       outcome was forced to the WAL.
 *   <li>{@link #REPLAYED} — the same key and payload were seen before; no second reservation.
 *   <li>{@link #REFUSED} — the wallet could not cover the amount (or a prior refusal for this
 *       key is being replayed).
 *   <li>{@link #CONFLICT} — the key was reused with a different payload; nothing was charged.
 * </ul>
 */
public enum ChargeStatus {
    ACCEPTED,
    REPLAYED,
    REFUSED,
    CONFLICT
}
