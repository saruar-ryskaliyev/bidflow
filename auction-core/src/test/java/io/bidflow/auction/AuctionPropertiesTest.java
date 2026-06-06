package io.bidflow.auction;

import static io.bidflow.auction.AuctionRequest.QUALITY_ONE_BPS;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

/**
 * The rules the auction must obey for <em>every</em> input, checked against generated
 * candidate sets rather than hand-picked ones.
 *
 * <p>An auction is a good fit for property-based testing because its guarantees are far
 * easier to state than to enumerate. "No advertiser is ever charged more than it bid" is
 * one line here and covers thousands of candidate sets, including the ones a human would
 * never think to write: everybody tied, a single campaign holding forty creatives, a
 * reserve that excludes all but one bidder. When a property fails, jqwik shrinks the input
 * to a minimal counterexample, which usually names the bug outright.
 *
 * <p>Campaign ids are drawn from a deliberately small pool so that duplicate campaigns —
 * the case the one-slot-per-campaign rule exists for — show up in most generated sets.
 */
@Label("auction invariants")
class AuctionPropertiesTest {

    private static final int MAX_CANDIDATES = 40;
    private static final int MAX_SLOTS = 8;
    private static final long MAX_BID = 1_000_000L;
    private static final long MAX_RESERVE = 200_000L;

    /** Absent from the outcome; compares as worse than any real position. */
    private static final int UNPLACED = Integer.MAX_VALUE;

    record Candidate(long campaignId, long bidMicros, int qualityBps) {}

    @Provide
    Arbitrary<List<Candidate>> candidateSets() {
        Arbitrary<Candidate> candidate = Combinators.combine(
                        Arbitraries.longs().between(1L, 12L),
                        Arbitraries.longs().between(0L, MAX_BID),
                        Arbitraries.integers().between(1, QUALITY_ONE_BPS))
                .as(Candidate::new);
        return candidate.list().ofMaxSize(MAX_CANDIDATES);
    }

    @Property
    @Label("no advertiser is charged more than it bid")
    void priceNeverExceedsTheBid(
            @ForAll("candidateSets") List<Candidate> candidates,
            @ForAll @IntRange(max = MAX_SLOTS) int slots,
            @ForAll @LongRange(max = MAX_RESERVE) long reserve) {
        final AuctionOutcome outcome = runAuction(candidates, slots, reserve);

        for (int k = 0; k < outcome.size(); k++) {
            // adRank == bid * quality by construction, so this division recovers the
            // winning candidate's bid exactly without the test having to search for it.
            final long bid = outcome.adRank(k) / outcome.qualityBps(k);
            assertThat(outcome.priceMicros(k))
                    .as("price at position %d", k)
                    .isLessThanOrEqualTo(bid);
        }
    }

    @Property
    @Label("no winner pays below the quality-adjusted floor")
    void priceNeverFallsBelowTheFloor(
            @ForAll("candidateSets") List<Candidate> candidates,
            @ForAll @IntRange(max = MAX_SLOTS) int slots,
            @ForAll @LongRange(max = MAX_RESERVE) long reserve) {
        final AuctionOutcome outcome = runAuction(candidates, slots, reserve);
        final long reserveRank = reserve * QUALITY_ONE_BPS;

        for (int k = 0; k < outcome.size(); k++) {
            final long floor = Math.ceilDiv(reserveRank, outcome.qualityBps(k));
            assertThat(outcome.priceMicros(k))
                    .as("price at position %d against its floor", k)
                    .isGreaterThanOrEqualTo(floor);
        }
    }

    @Property
    @Label("slots are ordered by descending ad rank")
    void ranksDescendDownThePage(
            @ForAll("candidateSets") List<Candidate> candidates,
            @ForAll @IntRange(max = MAX_SLOTS) int slots,
            @ForAll @LongRange(max = MAX_RESERVE) long reserve) {
        final AuctionOutcome outcome = runAuction(candidates, slots, reserve);

        for (int k = 1; k < outcome.size(); k++) {
            assertThat(outcome.adRank(k))
                    .as("rank at position %d", k)
                    .isLessThanOrEqualTo(outcome.adRank(k - 1));
        }
    }

    @Property
    @Label("a campaign never occupies two slots")
    void campaignsAppearAtMostOnce(
            @ForAll("candidateSets") List<Candidate> candidates,
            @ForAll @IntRange(max = MAX_SLOTS) int slots,
            @ForAll @LongRange(max = MAX_RESERVE) long reserve) {
        final AuctionOutcome outcome = runAuction(candidates, slots, reserve);

        final Set<Long> seen = new HashSet<>();
        for (int k = 0; k < outcome.size(); k++) {
            assertThat(seen.add(outcome.campaignId(k)))
                    .as("campaign %d repeated at position %d", outcome.campaignId(k), k)
                    .isTrue();
        }
    }

    @Property
    @Label("every winner cleared the reserve")
    void winnersClearTheReserve(
            @ForAll("candidateSets") List<Candidate> candidates,
            @ForAll @IntRange(max = MAX_SLOTS) int slots,
            @ForAll @LongRange(max = MAX_RESERVE) long reserve) {
        final AuctionOutcome outcome = runAuction(candidates, slots, reserve);
        final long reserveRank = reserve * QUALITY_ONE_BPS;

        for (int k = 0; k < outcome.size(); k++) {
            assertThat(outcome.adRank(k)).as("rank at position %d", k).isGreaterThanOrEqualTo(reserveRank);
        }
    }

    @Property
    @Label("no slot is left empty while an eligible campaign waits")
    void fillsEverySlotItCan(
            @ForAll("candidateSets") List<Candidate> candidates,
            @ForAll @IntRange(max = MAX_SLOTS) int slots,
            @ForAll @LongRange(max = MAX_RESERVE) long reserve) {
        final AuctionOutcome outcome = runAuction(candidates, slots, reserve);
        final long reserveRank = reserve * QUALITY_ONE_BPS;

        final Set<Long> eligibleCampaigns = new HashSet<>();
        for (Candidate c : candidates) {
            if (c.bidMicros() * c.qualityBps() >= reserveRank) {
                eligibleCampaigns.add(c.campaignId());
            }
        }
        assertThat(outcome.size()).isEqualTo(Math.min(slots, eligibleCampaigns.size()));
    }

    @Property
    @Label("the same inputs always produce the same outcome")
    void auctionsAreDeterministic(
            @ForAll("candidateSets") List<Candidate> candidates,
            @ForAll @IntRange(max = MAX_SLOTS) int slots,
            @ForAll @LongRange(max = MAX_RESERVE) long reserve) {
        final AuctionOutcome first = runAuction(candidates, slots, reserve);
        final AuctionOutcome second = runAuction(candidates, slots, reserve);

        assertThat(second.size()).isEqualTo(first.size());
        for (int k = 0; k < first.size(); k++) {
            assertThat(second.campaignId(k)).isEqualTo(first.campaignId(k));
            assertThat(second.priceMicros(k)).isEqualTo(first.priceMicros(k));
            assertThat(second.adRank(k)).isEqualTo(first.adRank(k));
        }
    }

    @Property
    @Label("bidding more never costs a campaign its position")
    void raisingABidNeverCostsPosition(
            @ForAll("candidateSets") List<Candidate> candidates,
            @ForAll @IntRange(min = 1, max = MAX_SLOTS) int slots,
            @ForAll @LongRange(max = MAX_RESERVE) long reserve,
            @ForAll @IntRange(max = MAX_CANDIDATES) int targetSeed,
            @ForAll @LongRange(min = 1L, max = MAX_BID) long raise) {
        Assume.that(!candidates.isEmpty());

        // Fold the generated index into range rather than discarding out-of-range draws,
        // which would push jqwik past its discard budget on the many short candidate sets.
        final int target = targetSeed % candidates.size();
        final Candidate original = candidates.get(target);

        final int before = positionOf(runAuction(candidates, slots, reserve), original.campaignId());

        final List<Candidate> raised = new ArrayList<>(candidates);
        raised.set(
                target,
                new Candidate(original.campaignId(), original.bidMicros() + raise, original.qualityBps()));
        final int after = positionOf(runAuction(raised, slots, reserve), original.campaignId());

        assertThat(after)
                .as("campaign %d moved from position %d to %d after raising its bid",
                        original.campaignId(), before, after)
                .isLessThanOrEqualTo(before);
    }

    private static int positionOf(AuctionOutcome outcome, long campaignId) {
        for (int k = 0; k < outcome.size(); k++) {
            if (outcome.campaignId(k) == campaignId) {
                return k;
            }
        }
        return UNPLACED;
    }

    private static AuctionOutcome runAuction(List<Candidate> candidates, int slots, long reserve) {
        final AuctionEngine engine = new AuctionEngine(MAX_CANDIDATES);
        final AuctionRequest request = new AuctionRequest(MAX_CANDIDATES).reset(slots, reserve);
        for (Candidate c : candidates) {
            request.add(c.campaignId(), c.bidMicros(), c.qualityBps());
        }
        final AuctionOutcome outcome = new AuctionOutcome(MAX_SLOTS);
        engine.run(request, outcome);
        return outcome;
    }
}
