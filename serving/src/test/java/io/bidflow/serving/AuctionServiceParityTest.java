package io.bidflow.serving;

import static io.bidflow.auction.AuctionRequest.QUALITY_ONE_BPS;
import static org.assertj.core.api.Assertions.assertThat;

import io.bidflow.auction.AuctionEngine;
import io.bidflow.auction.AuctionOutcome;
import io.bidflow.auction.AuctionRequest;
import io.bidflow.serving.v1.AuctionServiceGrpc;
import io.bidflow.serving.v1.Candidate;
import io.bidflow.serving.v1.RunAuctionRequest;
import io.bidflow.serving.v1.RunAuctionResponse;
import io.grpc.ManagedChannel;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * The serving layer must add nothing to the mechanism: a gRPC round trip yields the same
 * winners and prices as a direct {@link AuctionEngine#run} on the same integers.
 */
@Label("serving layer parity with AuctionEngine")
class AuctionServiceParityTest {

    private static final int MAX_CANDIDATES = 40;
    private static final int MAX_SLOTS = 8;
    private static final long MAX_BID = 1_000_000L;
    private static final long MAX_RESERVE = 200_000L;

    record CandidateSpec(long campaignId, long bidMicros, int qualityBps) {}

    private String serverName;
    private AuctionServer auctionServer;
    private ManagedChannel channel;
    private AuctionServiceGrpc.AuctionServiceBlockingStub stub;
    private AuctionEngine engine;
    private AuctionRequest request;
    private AuctionOutcome outcome;

    @BeforeTry
    void setUp() throws Exception {
        serverName = InProcessServerBuilder.generateName();
        auctionServer = AuctionServer.inProcess(
                InProcessServerBuilder.forName(serverName).directExecutor(),
                2,
                8,
                MAX_CANDIDATES,
                MAX_SLOTS);
        auctionServer.start();
        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        stub = AuctionServiceGrpc.newBlockingStub(channel);
        engine = new AuctionEngine(MAX_CANDIDATES);
        request = new AuctionRequest(MAX_CANDIDATES);
        outcome = new AuctionOutcome(MAX_SLOTS);
    }

    @AfterTry
    void tearDown() throws Exception {
        channel.shutdownNow();
        channel.awaitTermination(5, TimeUnit.SECONDS);
        auctionServer.close();
    }

    @Provide
    Arbitrary<List<CandidateSpec>> candidateSets() {
        Arbitrary<CandidateSpec> candidate = Combinators.combine(
                        Arbitraries.longs().between(1L, 12L),
                        Arbitraries.longs().between(0L, MAX_BID),
                        Arbitraries.integers().between(1, QUALITY_ONE_BPS))
                .as(CandidateSpec::new);
        return candidate.list().ofMaxSize(MAX_CANDIDATES);
    }

    @Property(tries = 500)
    @Label("gRPC response is bit-identical to a direct engine run")
    void parityWithDirectEngine(
            @ForAll("candidateSets") List<CandidateSpec> candidates,
            @ForAll @IntRange(max = MAX_SLOTS) int slots,
            @ForAll @LongRange(max = MAX_RESERVE) long reserve) {
        request.reset(slots, reserve);
        final RunAuctionRequest.Builder proto = RunAuctionRequest.newBuilder()
                .setSlots(slots)
                .setReservePriceMicros(reserve);
        for (CandidateSpec c : candidates) {
            request.add(c.campaignId(), c.bidMicros(), c.qualityBps());
            proto.addCandidates(Candidate.newBuilder()
                    .setCampaignId(c.campaignId())
                    .setBidMicros(c.bidMicros())
                    .setQualityBps(c.qualityBps())
                    .build());
        }
        engine.run(request, outcome);

        final RunAuctionResponse response = stub.runAuction(proto.build());

        assertThat(response.getSlotsCount()).isEqualTo(outcome.size());
        for (int k = 0; k < outcome.size(); k++) {
            assertThat(response.getSlots(k).getCampaignId())
                    .as("campaign at position %d", k)
                    .isEqualTo(outcome.campaignId(k));
            assertThat(response.getSlots(k).getPriceMicros())
                    .as("price at position %d", k)
                    .isEqualTo(outcome.priceMicros(k));
            assertThat(response.getSlots(k).getAdRank())
                    .as("adRank at position %d", k)
                    .isEqualTo(outcome.adRank(k));
            assertThat(response.getSlots(k).getQualityBps())
                    .as("quality at position %d", k)
                    .isEqualTo(outcome.qualityBps(k));
        }
    }
}
