package io.bidflow.budget;

/**
 * One serving shard's wallet: the money it is currently allowed to spend without asking
 * anyone.
 *
 * <p>This sits directly on the request path, so it does one subtraction, one comparison, and
 * one addition. No locks, no allocation, no network. Whether an ad can afford to show is
 * answered from two {@code long} fields and nothing else — which is the entire point, because
 * consulting a central service would cost milliseconds inside a microsecond budget.
 *
 * <h2>Why both numbers are cumulative</h2>
 *
 * <p>The wallet tracks lifetime totals rather than a decrementing remainder, and grants
 * arrive as "your total authority is now N" rather than "here is 200 more". That choice makes
 * the protocol survive a bad network for free.
 *
 * <p>An incremental grant is dangerous: if "here is 200 more" is delivered twice — and a retry
 * of a message that was merely slow rather than lost looks exactly like that — the shard ends
 * up with 400 and overspends. A cumulative grant applied twice is harmless, because setting a
 * total to N twice leaves it at N. The same reasoning makes out-of-order delivery safe, since
 * {@link #applyGrant} keeps the highest total it has seen and a stale grant cannot claw back
 * authority already held.
 *
 * <h2>Incarnations</h2>
 *
 * <p>This object lives in memory, so a process crash destroys it. That matters more than it
 * looks: the replacement wallet has no idea what its predecessor spent, so if it were handed
 * the shard's lifetime authority total it would treat every micro already spent as still
 * available and cheerfully spend the budget twice.
 *
 * <p>So a wallet belongs to an <em>incarnation</em> — one lifetime of one process. A restarted
 * shard is a new incarnation starting from zero authority, and grants are scoped to the
 * incarnation that asked for them, so a reply intended for a dead predecessor is discarded
 * rather than applied. The authority its predecessor held is written off rather than reused,
 * which is safe and wasteful, and that waste is the thing worth measuring.
 *
 * <p>Money is {@code long} micros throughout, never floating point, so spend replays exactly
 * for billing.
 *
 * <p><b>Not thread-safe.</b> One instance per request-handling thread.
 */
public final class SpendAuthority {

    private final int shardId;
    private final long incarnation;

    /** Micros this incarnation has been authorised to spend. Never decreases. */
    private long authorityMicros;

    /** Micros this incarnation has committed. Never decreases. */
    private long spentMicros;

    /**
     * @param shardId which serving shard this wallet belongs to
     * @param incarnation strictly increasing per restart of that shard, so that grants aimed
     *     at a previous process are recognisable and can be ignored
     */
    public SpendAuthority(int shardId, long incarnation) {
        if (shardId < 0) {
            throw new IllegalArgumentException("shardId must not be negative, was " + shardId);
        }
        if (incarnation < 0) {
            throw new IllegalArgumentException("incarnation must not be negative, was " + incarnation);
        }
        this.shardId = shardId;
        this.incarnation = incarnation;
    }

    /**
     * Commits {@code amountMicros} of spend if this wallet still has the authority for it.
     *
     * <p>The hot path. Returns false rather than throwing when the wallet is empty, because
     * running out of budget is an ordinary outcome — the ad simply does not show.
     *
     * @return true if the spend was committed
     */
    public boolean tryReserve(long amountMicros) {
        if (amountMicros < 0) {
            throw new IllegalArgumentException("amountMicros must not be negative, was " + amountMicros);
        }
        // Written as a subtraction on the remaining side rather than an addition on the spent
        // side, so a huge amount cannot overflow the comparison into passing.
        if (authorityMicros - spentMicros < amountMicros) {
            return false;
        }
        spentMicros += amountMicros;
        return true;
    }

    /**
     * Applies a grant, which states this incarnation's total authority rather than an
     * increment.
     *
     * <p>Ignored unless the grant was issued to this incarnation. Keeping the maximum is what
     * makes it safe to apply the same grant twice, or out of order, or both.
     *
     * @return true if the grant changed anything
     */
    public boolean applyGrant(long grantIncarnation, long totalAuthorityMicros) {
        if (grantIncarnation != incarnation) {
            return false;
        }
        if (totalAuthorityMicros <= authorityMicros) {
            return false;
        }
        authorityMicros = totalAuthorityMicros;
        return true;
    }

    /** True when the wallet has fallen low enough to be worth topping up. */
    public boolean needsTopUp(long thresholdMicros) {
        return remainingMicros() <= thresholdMicros;
    }

    public int shardId() {
        return shardId;
    }

    public long incarnation() {
        return incarnation;
    }

    public long remainingMicros() {
        return authorityMicros - spentMicros;
    }

    /** This incarnation's spend, which is what gets reported back to the authority. */
    public long spentMicros() {
        return spentMicros;
    }

    public long authorityMicros() {
        return authorityMicros;
    }
}
