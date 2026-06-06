package io.bidflow.auction;

/**
 * A generalized second-price (GSP) auction, the mechanism that prices sponsored search
 * results.
 *
 * <h2>Ranking</h2>
 *
 * <p>Candidates are ordered by <em>ad rank</em>, the product of the bid and the predicted
 * quality:
 *
 * <pre>{@code adRank = bidMicros * qualityBps}</pre>
 *
 * <p>Ranking on the product rather than on the bid alone is what stops the top slot from
 * simply going to the deepest pocket: a merely adequate ad has to outbid a good one by the
 * ratio of their quality scores. Candidates whose ad rank falls below the reserve are
 * discarded, and a campaign may win at most one slot per auction.
 *
 * <p>Ties are broken by the lower campaign id. Any total order would do, but it must be a
 * deterministic one — an auction replayed from a billing log has to reproduce the original
 * result exactly.
 *
 * <h2>Pricing</h2>
 *
 * <p>Under GSP, a winner does not pay its own bid. It pays the smallest amount it could
 * have bid and still held the slot it won, which is the ad rank of the candidate directly
 * below it converted back into a price at the winner's own quality:
 *
 * <pre>{@code price_k = ceil(adRank_{k+1} / quality_k)}</pre>
 *
 * <p>Dividing by the winner's own quality is the important detail: two ads defending the
 * same slot against the same rival pay different amounts, and the higher-quality one pays
 * less. The bottom winner is priced against the best candidate that failed to win a slot,
 * or against the reserve if every eligible candidate won one.
 *
 * <p>Division rounds up, so the price is always at least enough to hold the slot; the
 * rounding is what serves as the minimum bid increment.
 *
 * <h2>Cost and allocation</h2>
 *
 * <p>Selection is a partial selection sort that orders only the first {@code slots + 1}
 * candidates, costing {@code O(n * slots)} rather than the {@code O(n log n)} of a full
 * sort. With the handful of slots a real page offers, that is both fewer comparisons and
 * no allocation, which a comparator-based sort could not avoid.
 *
 * <p>Every buffer the auction needs is allocated once in the constructor, so a warmed
 * steady state allocates nothing per auction and gives the collector no work to do.
 *
 * <p><b>Not thread-safe.</b> The engine carries mutable scratch state; give each
 * request-handling thread its own instance rather than synchronizing.
 */
public final class AuctionEngine {

    /** Candidate indices into the request, partially ordered by descending ad rank. */
    private final int[] order;

    /** Ad rank of {@code order[i]}, kept alongside so the ranking loop reads one array. */
    private final long[] rank;

    /**
     * @param maxCandidates the largest candidate set any request may carry; requests
     *     exceeding this are rejected rather than silently truncated
     */
    public AuctionEngine(int maxCandidates) {
        if (maxCandidates <= 0) {
            throw new IllegalArgumentException("maxCandidates must be positive, was " + maxCandidates);
        }
        this.order = new int[maxCandidates];
        this.rank = new long[maxCandidates];
    }

    public int maxCandidates() {
        return order.length;
    }

    /**
     * Runs one auction, overwriting {@code outcome} with the winners in slot order.
     *
     * @param request the candidate set; not modified
     * @param outcome destination buffer, cleared first, whose capacity must cover the
     *     slots the request offers
     */
    public void run(AuctionRequest request, AuctionOutcome outcome) {
        outcome.reset();

        if (request.size() > order.length) {
            throw new IllegalArgumentException(
                    "request holds " + request.size() + " candidates, engine sized for " + order.length);
        }
        final int slots = request.slots();
        if (slots > outcome.capacity()) {
            throw new IllegalArgumentException(
                    "request offers " + slots + " slots, outcome sized for " + outcome.capacity());
        }
        if (slots == 0) {
            return;
        }

        int eligible = collectEligible(request);
        if (eligible == 0) {
            return;
        }
        eligible = selectTop(request, eligible, slots + 1);
        award(request, outcome, Math.min(slots, eligible), eligible);
    }

    /**
     * Compacts the candidates clearing the reserve into the scratch buffers, computing
     * each ad rank once so later passes never revisit the request.
     *
     * @return how many candidates are eligible
     */
    private int collectEligible(AuctionRequest request) {
        final long reserve = request.reserveRank();
        final int n = request.size();
        int eligible = 0;
        for (int i = 0; i < n; i++) {
            // Cannot overflow: bids and quality are range-checked on the way in.
            final long adRank = request.bidMicros(i) * request.qualityBps(i);
            if (adRank >= reserve) {
                order[eligible] = i;
                rank[eligible] = adRank;
                eligible++;
            }
        }
        return eligible;
    }

    /**
     * Moves the best {@code wanted} candidates into the front of the scratch buffers in
     * descending rank order, dropping the losing candidates of any campaign that has
     * already won a slot.
     *
     * @return the eligible count after duplicate campaigns were removed
     */
    private int selectTop(AuctionRequest request, int eligible, int wanted) {
        int count = eligible;
        for (int k = 0; k < wanted && k < count; k++) {
            int best = k;
            for (int j = k + 1; j < count; j++) {
                if (outranks(request, j, best)) {
                    best = j;
                }
            }
            if (best != k) {
                swap(k, best);
            }

            // One slot per campaign: retire the winner's remaining candidates by swapping
            // them past the live range, which keeps the survivors contiguous.
            final long winner = request.campaignId(order[k]);
            int j = k + 1;
            while (j < count) {
                if (request.campaignId(order[j]) == winner) {
                    count--;
                    swap(j, count);
                } else {
                    j++;
                }
            }
        }
        return count;
    }

    private boolean outranks(AuctionRequest request, int a, int b) {
        if (rank[a] != rank[b]) {
            return rank[a] > rank[b];
        }
        return request.campaignId(order[a]) < request.campaignId(order[b]);
    }

    private void swap(int a, int b) {
        final int tmpOrder = order[a];
        order[a] = order[b];
        order[b] = tmpOrder;

        final long tmpRank = rank[a];
        rank[a] = rank[b];
        rank[b] = tmpRank;
    }

    /** Prices each winning slot and records it. */
    private void award(AuctionRequest request, AuctionOutcome outcome, int allocated, int eligible) {
        for (int k = 0; k < allocated; k++) {
            final int candidate = order[k];
            final int quality = request.qualityBps(candidate);

            // The rank this winner had to beat: the ad below it, or the reserve if it is
            // the last eligible candidate. Every eligible rank already clears the reserve,
            // so this is never below the floor price.
            final long benchmark = (k + 1 < eligible) ? rank[k + 1] : request.reserveRank();

            long price = Math.ceilDiv(benchmark, quality);

            // Redundant given benchmark <= rank[k] == bid * quality, but overcharging an
            // advertiser beyond its stated maximum is the one failure worth guarding twice.
            final long bid = request.bidMicros(candidate);
            if (price > bid) {
                price = bid;
            }
            outcome.add(request.campaignId(candidate), price, rank[k], quality);
        }
    }
}
