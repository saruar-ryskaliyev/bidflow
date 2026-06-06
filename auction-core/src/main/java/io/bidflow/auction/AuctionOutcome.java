package io.bidflow.auction;

/**
 * The ordered winners of one auction and the price each pays.
 *
 * <p>Like {@link AuctionRequest} this is a reusable struct-of-arrays buffer rather than a
 * freshly allocated list of result objects, so reporting an outcome costs no allocation.
 * Index 0 is the top position.
 *
 * <p><b>Not thread-safe.</b> Confine one instance to one request-handling thread.
 */
public final class AuctionOutcome {

    private final long[] campaignIds;
    private final long[] priceMicros;
    private final long[] adRanks;
    private final int[] qualityBps;

    private int size;

    /** @param maxSlots the largest number of winners any auction may produce */
    public AuctionOutcome(int maxSlots) {
        if (maxSlots <= 0) {
            throw new IllegalArgumentException("maxSlots must be positive, was " + maxSlots);
        }
        this.campaignIds = new long[maxSlots];
        this.priceMicros = new long[maxSlots];
        this.adRanks = new long[maxSlots];
        this.qualityBps = new int[maxSlots];
    }

    void reset() {
        size = 0;
    }

    void add(long campaignId, long price, long adRank, int quality) {
        campaignIds[size] = campaignId;
        priceMicros[size] = price;
        adRanks[size] = adRank;
        qualityBps[size] = quality;
        size++;
    }

    /** Number of slots actually filled, which may be fewer than the slots offered. */
    public int size() {
        return size;
    }

    public int capacity() {
        return campaignIds.length;
    }

    /** @param position zero-based slot, 0 being the top */
    public long campaignId(int position) {
        return campaignIds[checked(position)];
    }

    /** The amount charged for a click on this position, in micros. */
    public long priceMicros(int position) {
        return priceMicros[checked(position)];
    }

    public long adRank(int position) {
        return adRanks[checked(position)];
    }

    public int qualityBps(int position) {
        return qualityBps[checked(position)];
    }

    /** Total charge if every winning position were clicked once, in micros. */
    public long totalPriceMicros() {
        long total = 0;
        for (int i = 0; i < size; i++) {
            total += priceMicros[i];
        }
        return total;
    }

    private int checked(int position) {
        if (position < 0 || position >= size) {
            throw new IndexOutOfBoundsException("position " + position + " not in [0, " + size + ")");
        }
        return position;
    }
}
