package io.bidflow.auction;

/**
 * The candidate ads competing for the slots of a single auction.
 *
 * <p>Candidates are held as a struct-of-arrays rather than an array of objects. Three
 * parallel primitive arrays keep the fields the ranking loop actually reads contiguous
 * in memory, and they let the whole auction run without allocating or dereferencing.
 *
 * <p>Instances are sized once and reused: call {@link #reset} then {@link #add} for each
 * candidate. Reuse is what keeps the steady-state allocation rate at zero, so a serving
 * thread should hold one instance for its lifetime.
 *
 * <p><b>Not thread-safe.</b> Confine one instance to one request-handling thread.
 *
 * <h2>Money and quality</h2>
 *
 * <p>All monetary amounts are {@code long} counts of micros — millionths of the account
 * currency unit. Money is never represented as {@code double} or {@code float}: binary
 * floating point cannot represent decimal currency exactly, and an auction that is
 * replayed for billing must produce bit-identical results.
 *
 * <p>Quality is an integer in basis points, where {@link #QUALITY_ONE_BPS} represents a
 * quality multiplier of 1.0. Keeping quality integral means ad rank is exact integer
 * arithmetic, so ranking is fully deterministic and free of rounding drift.
 */
public final class AuctionRequest {

    /** Basis-point value representing a quality multiplier of exactly 1.0. */
    public static final int QUALITY_ONE_BPS = 10_000;

    /**
     * Largest accepted bid, in micros — one billion micros, i.e. 1,000 currency units
     * per click. The cap exists so that {@code bidMicros * qualityBps} cannot overflow
     * a {@code long}, which lets the ranking loop multiply without any overflow check.
     */
    public static final long MAX_BID_MICROS = 1_000_000_000L;

    private final long[] campaignIds;
    private final long[] bidMicros;
    private final int[] qualityBps;

    private int size;
    private int slots;
    private long reserveRank;

    /**
     * @param capacity maximum number of candidates this request can hold; the retrieval
     *     stage upstream is expected to truncate to this many
     */
    public AuctionRequest(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive, was " + capacity);
        }
        this.campaignIds = new long[capacity];
        this.bidMicros = new long[capacity];
        this.qualityBps = new int[capacity];
    }

    /**
     * Clears the candidate list and prepares this request for reuse.
     *
     * @param slots number of ad positions available to fill
     * @param reservePriceMicros the floor price a candidate of quality 1.0 must bid. The
     *     floor is quality-adjusted: it is converted to a reserve on <em>ad rank</em>, so
     *     a high-quality ad clears the same reserve with a lower bid than a low-quality
     *     one. This is the mechanism that makes relevance cheaper than brute-force bidding.
     * @return this instance, for chaining
     */
    public AuctionRequest reset(int slots, long reservePriceMicros) {
        if (slots < 0) {
            throw new IllegalArgumentException("slots must not be negative, was " + slots);
        }
        if (reservePriceMicros < 0 || reservePriceMicros > MAX_BID_MICROS) {
            throw new IllegalArgumentException(
                    "reservePriceMicros must be in [0, " + MAX_BID_MICROS + "], was " + reservePriceMicros);
        }
        this.size = 0;
        this.slots = slots;
        this.reserveRank = reservePriceMicros * QUALITY_ONE_BPS;
        return this;
    }

    /**
     * Adds one candidate.
     *
     * @param campaignId owning campaign; a campaign wins at most one slot per auction, so
     *     several candidates may share an id and the engine will keep only the best
     * @param bidMicros maximum the campaign will pay per click, in micros
     * @param qualityBps predicted quality in basis points, in {@code [1, QUALITY_ONE_BPS]}
     * @return this instance, for chaining
     */
    public AuctionRequest add(long campaignId, long bidMicros, int qualityBps) {
        if (size == campaignIds.length) {
            throw new IllegalStateException("candidate capacity exhausted: " + campaignIds.length);
        }
        if (bidMicros < 0 || bidMicros > MAX_BID_MICROS) {
            throw new IllegalArgumentException(
                    "bidMicros must be in [0, " + MAX_BID_MICROS + "], was " + bidMicros);
        }
        if (qualityBps < 1 || qualityBps > QUALITY_ONE_BPS) {
            throw new IllegalArgumentException(
                    "qualityBps must be in [1, " + QUALITY_ONE_BPS + "], was " + qualityBps);
        }
        campaignIds[size] = campaignId;
        this.bidMicros[size] = bidMicros;
        this.qualityBps[size] = qualityBps;
        size++;
        return this;
    }

    public int size() {
        return size;
    }

    public int slots() {
        return slots;
    }

    public int capacity() {
        return campaignIds.length;
    }

    /** The eligibility threshold on ad rank, derived from the reserve price. */
    public long reserveRank() {
        return reserveRank;
    }

    public long campaignId(int index) {
        return campaignIds[index];
    }

    public long bidMicros(int index) {
        return bidMicros[index];
    }

    public int qualityBps(int index) {
        return qualityBps[index];
    }
}
