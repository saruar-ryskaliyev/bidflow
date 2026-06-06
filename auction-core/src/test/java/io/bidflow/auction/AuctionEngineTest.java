package io.bidflow.auction;

import static io.bidflow.auction.AuctionRequest.QUALITY_ONE_BPS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Worked examples pinning the auction's arithmetic.
 *
 * <p>The numbers here are chosen by hand rather than generated, so a regression names the
 * specific rule it broke. The generated coverage lives in {@link AuctionPropertiesTest}.
 */
class AuctionEngineTest {

    private static final int MAX_CANDIDATES = 64;
    private static final int MAX_SLOTS = 8;

    private AuctionEngine engine;
    private AuctionRequest request;
    private AuctionOutcome outcome;

    @BeforeEach
    void setUp() {
        engine = new AuctionEngine(MAX_CANDIDATES);
        request = new AuctionRequest(MAX_CANDIDATES);
        outcome = new AuctionOutcome(MAX_SLOTS);
    }

    @Nested
    @DisplayName("ranking")
    class Ranking {

        @Test
        @DisplayName("orders by bid times quality, not by bid alone")
        void qualityCanBeatABiggerBid() {
            request.reset(2, 0)
                    .add(1L, 100_000L, QUALITY_ONE_BPS)      // rank 1.0e9
                    .add(2L, 60_000L, QUALITY_ONE_BPS);      // rank 6.0e8
            engine.run(request, outcome);
            assertThat(outcome.campaignId(0)).isEqualTo(1L);

            // Halving the leader's quality halves its rank, which is now the lower of the
            // two even though its bid is still the larger.
            request.reset(2, 0)
                    .add(1L, 100_000L, QUALITY_ONE_BPS / 2)  // rank 5.0e8
                    .add(2L, 60_000L, QUALITY_ONE_BPS);      // rank 6.0e8
            engine.run(request, outcome);
            assertThat(outcome.campaignId(0)).isEqualTo(2L);
        }

        @Test
        @DisplayName("fills no more slots than the page offers")
        void respectsSlotCount() {
            request.reset(2, 0);
            for (long campaign = 1; campaign <= 5; campaign++) {
                request.add(campaign, 10_000L * campaign, QUALITY_ONE_BPS);
            }
            engine.run(request, outcome);

            assertThat(outcome.size()).isEqualTo(2);
            assertThat(outcome.campaignId(0)).isEqualTo(5L);
            assertThat(outcome.campaignId(1)).isEqualTo(4L);
        }

        @Test
        @DisplayName("fills fewer slots than offered when candidates run out")
        void toleratesThinCandidateSets() {
            request.reset(5, 0).add(7L, 50_000L, QUALITY_ONE_BPS);
            engine.run(request, outcome);
            assertThat(outcome.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("gives a campaign at most one slot, keeping its strongest creative")
        void deduplicatesCampaigns() {
            request.reset(3, 0)
                    .add(1L, 10_000L, QUALITY_ONE_BPS)
                    .add(1L, 90_000L, QUALITY_ONE_BPS)   // same campaign, stronger creative
                    .add(2L, 50_000L, QUALITY_ONE_BPS);
            engine.run(request, outcome);

            assertThat(outcome.size()).isEqualTo(2);
            assertThat(outcome.campaignId(0)).isEqualTo(1L);
            assertThat(outcome.adRank(0)).isEqualTo(90_000L * QUALITY_ONE_BPS);
            assertThat(outcome.campaignId(1)).isEqualTo(2L);
        }

        @Test
        @DisplayName("breaks exact rank ties deterministically by campaign id")
        void breaksTiesDeterministically() {
            request.reset(2, 0)
                    .add(9L, 50_000L, QUALITY_ONE_BPS)
                    .add(4L, 50_000L, QUALITY_ONE_BPS);
            engine.run(request, outcome);

            assertThat(outcome.campaignId(0)).isEqualTo(4L);
            assertThat(outcome.campaignId(1)).isEqualTo(9L);
        }
    }

    @Nested
    @DisplayName("pricing")
    class Pricing {

        @Test
        @DisplayName("charges the winner just enough to hold off the runner-up")
        void winnerPaysSecondPrice() {
            request.reset(1, 0)
                    .add(1L, 100_000L, QUALITY_ONE_BPS)
                    .add(2L, 60_000L, QUALITY_ONE_BPS);
            engine.run(request, outcome);

            // Equal quality, so the runner-up's rank converts straight back to its bid.
            assertThat(outcome.priceMicros(0)).isEqualTo(60_000L);
        }

        @Test
        @DisplayName("charges a higher-quality winner less to hold the same slot")
        void qualityDiscountsThePrice() {
            request.reset(1, 0)
                    .add(1L, 100_000L, QUALITY_ONE_BPS)
                    .add(2L, 30_000L, QUALITY_ONE_BPS);
            engine.run(request, outcome);
            assertThat(outcome.campaignId(0)).isEqualTo(1L);
            final long priceAtFullQuality = outcome.priceMicros(0);

            // Same rival, same bid, but the winner is now half as relevant. Its lead is
            // still wide enough to hold the slot, so this isolates the effect on price:
            // reaching the rank it is defending now costs twice as much per click.
            request.reset(1, 0)
                    .add(1L, 100_000L, QUALITY_ONE_BPS / 2)
                    .add(2L, 30_000L, QUALITY_ONE_BPS);
            engine.run(request, outcome);

            assertThat(outcome.campaignId(0)).isEqualTo(1L);
            assertThat(outcome.priceMicros(0)).isEqualTo(2 * priceAtFullQuality);
        }

        @Test
        @DisplayName("never charges more than the advertiser's maximum bid")
        void priceIsCappedByTheBid() {
            request.reset(1, 0).add(1L, 40_000L, QUALITY_ONE_BPS);
            engine.run(request, outcome);
            assertThat(outcome.priceMicros(0)).isLessThanOrEqualTo(40_000L);
        }

        @Test
        @DisplayName("falls back to the reserve when nobody is left to bid against")
        void soleBidderPaysTheReserve() {
            request.reset(1, 25_000L).add(1L, 90_000L, QUALITY_ONE_BPS);
            engine.run(request, outcome);
            assertThat(outcome.priceMicros(0)).isEqualTo(25_000L);
        }

        @Test
        @DisplayName("discounts the reserve for a high-quality ad")
        void reserveIsQualityAdjusted() {
            // Reserve is a floor on rank, so an ad at half quality must pay double the
            // headline reserve to clear the same bar.
            request.reset(1, 20_000L).add(1L, 90_000L, QUALITY_ONE_BPS / 2);
            engine.run(request, outcome);
            assertThat(outcome.priceMicros(0)).isEqualTo(40_000L);
        }

        @Test
        @DisplayName("prices each position against the one below it")
        void everyPositionIsPricedAgainstItsSuccessor() {
            request.reset(3, 5_000L)
                    .add(1L, 100_000L, QUALITY_ONE_BPS)
                    .add(2L, 80_000L, QUALITY_ONE_BPS)
                    .add(3L, 60_000L, QUALITY_ONE_BPS)
                    .add(4L, 40_000L, QUALITY_ONE_BPS);
            engine.run(request, outcome);

            assertThat(outcome.size()).isEqualTo(3);
            assertThat(outcome.priceMicros(0)).isEqualTo(80_000L);
            assertThat(outcome.priceMicros(1)).isEqualTo(60_000L);
            assertThat(outcome.priceMicros(2)).isEqualTo(40_000L);
            assertThat(outcome.totalPriceMicros()).isEqualTo(180_000L);
        }

        @Test
        @DisplayName("rounds a fractional price up, so it always covers the slot")
        void roundsPriceUp() {
            // Rival rank 3 * 9_999 = 29_997; winner quality 9_999 would need 3.0000 exactly,
            // so pick a rival rank that does not divide evenly.
            request.reset(1, 0)
                    .add(1L, 100L, 9_999)
                    .add(2L, 10L, 5_000);   // rank 50_000
            engine.run(request, outcome);

            // 50_000 / 9_999 = 5.0005..., rounded up to 6.
            assertThat(outcome.priceMicros(0)).isEqualTo(6L);
        }
    }

    @Nested
    @DisplayName("eligibility")
    class Eligibility {

        @Test
        @DisplayName("drops candidates that cannot clear the reserve")
        void reserveExcludesWeakCandidates() {
            request.reset(3, 50_000L)
                    .add(1L, 90_000L, QUALITY_ONE_BPS)
                    .add(2L, 49_999L, QUALITY_ONE_BPS)
                    .add(3L, 10_000L, QUALITY_ONE_BPS);
            engine.run(request, outcome);

            assertThat(outcome.size()).isEqualTo(1);
            assertThat(outcome.campaignId(0)).isEqualTo(1L);
        }

        @Test
        @DisplayName("returns an empty outcome when the reserve clears the field")
        void reserveCanEmptyTheAuction() {
            request.reset(3, 100_000L).add(1L, 10_000L, QUALITY_ONE_BPS);
            engine.run(request, outcome);

            assertThat(outcome.size()).isZero();
            assertThat(outcome.totalPriceMicros()).isZero();
        }

        @Test
        @DisplayName("returns an empty outcome when the page offers no slots")
        void zeroSlotsYieldsNothing() {
            request.reset(0, 0).add(1L, 90_000L, QUALITY_ONE_BPS);
            engine.run(request, outcome);
            assertThat(outcome.size()).isZero();
        }

        @Test
        @DisplayName("returns an empty outcome when there are no candidates at all")
        void emptyCandidateSetYieldsNothing() {
            request.reset(3, 0);
            engine.run(request, outcome);
            assertThat(outcome.size()).isZero();
        }
    }

    @Nested
    @DisplayName("contracts")
    class Contracts {

        @Test
        @DisplayName("rejects a bid outside the representable range")
        void rejectsOutOfRangeBid() {
            assertThatThrownBy(() -> request.reset(1, 0).add(1L, AuctionRequest.MAX_BID_MICROS + 1, QUALITY_ONE_BPS))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("bidMicros");
        }

        @Test
        @DisplayName("rejects a quality score outside basis-point range")
        void rejectsOutOfRangeQuality() {
            assertThatThrownBy(() -> request.reset(1, 0).add(1L, 10_000L, QUALITY_ONE_BPS + 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("qualityBps");
            assertThatThrownBy(() -> request.reset(1, 0).add(1L, 10_000L, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("qualityBps");
        }

        @Test
        @DisplayName("rejects more slots than the outcome buffer can hold")
        void rejectsUndersizedOutcome() {
            request.reset(MAX_SLOTS + 1, 0).add(1L, 10_000L, QUALITY_ONE_BPS);
            assertThatThrownBy(() -> engine.run(request, outcome))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("outcome sized for");
        }

        @Test
        @DisplayName("rejects a candidate set larger than the engine was sized for")
        void rejectsOversizedCandidateSet() {
            final AuctionEngine small = new AuctionEngine(1);
            request.reset(1, 0)
                    .add(1L, 10_000L, QUALITY_ONE_BPS)
                    .add(2L, 10_000L, QUALITY_ONE_BPS);
            assertThatThrownBy(() -> small.run(request, outcome))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("engine sized for");
        }

        @Test
        @DisplayName("refuses to read a slot that was not filled")
        void readingAnUnfilledSlotFails() {
            request.reset(1, 0).add(1L, 10_000L, QUALITY_ONE_BPS);
            engine.run(request, outcome);
            assertThatThrownBy(() -> outcome.campaignId(1)).isInstanceOf(IndexOutOfBoundsException.class);
        }

        @Test
        @DisplayName("clears the previous result when reused")
        void reuseDoesNotLeakPriorWinners() {
            request.reset(3, 0)
                    .add(1L, 90_000L, QUALITY_ONE_BPS)
                    .add(2L, 80_000L, QUALITY_ONE_BPS)
                    .add(3L, 70_000L, QUALITY_ONE_BPS);
            engine.run(request, outcome);
            assertThat(outcome.size()).isEqualTo(3);

            request.reset(3, 1_000_000L).add(1L, 90_000L, QUALITY_ONE_BPS);
            engine.run(request, outcome);
            assertThat(outcome.size()).isZero();
        }
    }
}
