package io.bidflow.serving;

import static io.bidflow.auction.AuctionRequest.QUALITY_ONE_BPS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.bidflow.serving.v1.AuctionServiceGrpc;
import io.bidflow.serving.v1.Candidate;
import io.bidflow.serving.v1.RunAuctionRequest;
import io.bidflow.serving.v1.RunAuctionResponse;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Worked examples and validation through the gRPC boundary.
 *
 * <p>The auction arithmetic itself is covered in {@code auction-core}; these tests pin that
 * the serving layer preserves it and that bad inputs become {@code INVALID_ARGUMENT}.
 */
class AuctionServiceTest {

    private static final int MAX_CANDIDATES = 64;
    private static final int MAX_SLOTS = 8;

    private String serverName;
    private AuctionServer auctionServer;
    private ManagedChannel channel;
    private AuctionServiceGrpc.AuctionServiceBlockingStub stub;

    @BeforeEach
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
    }

    @AfterEach
    void tearDown() throws Exception {
        channel.shutdownNow();
        channel.awaitTermination(5, TimeUnit.SECONDS);
        auctionServer.close();
    }

    @Nested
    @DisplayName("correctness")
    class Correctness {

        @Test
        @DisplayName("README worked example: A/B/C/D, three slots, reserve 5_000")
        void workedExample() {
            final RunAuctionResponse response = stub.runAuction(RunAuctionRequest.newBuilder()
                    .setSlots(3)
                    .setReservePriceMicros(5_000L)
                    .addCandidates(candidate(1L, 100_000L, QUALITY_ONE_BPS))
                    .addCandidates(candidate(2L, 80_000L, QUALITY_ONE_BPS))
                    .addCandidates(candidate(3L, 60_000L, QUALITY_ONE_BPS))
                    .addCandidates(candidate(4L, 40_000L, QUALITY_ONE_BPS))
                    .build());

            assertThat(response.getSlotsCount()).isEqualTo(3);
            assertThat(response.getSlots(0).getCampaignId()).isEqualTo(1L);
            assertThat(response.getSlots(0).getPriceMicros()).isEqualTo(80_000L);
            assertThat(response.getSlots(1).getCampaignId()).isEqualTo(2L);
            assertThat(response.getSlots(1).getPriceMicros()).isEqualTo(60_000L);
            assertThat(response.getSlots(2).getCampaignId()).isEqualTo(3L);
            assertThat(response.getSlots(2).getPriceMicros()).isEqualTo(40_000L);
            assertThat(auctionServer.served()).isEqualTo(1L);
        }

        @Test
        @DisplayName("empty candidate set returns no slots")
        void emptyCandidateSet() {
            final RunAuctionResponse response = stub.runAuction(RunAuctionRequest.newBuilder()
                    .setSlots(3)
                    .setReservePriceMicros(5_000L)
                    .build());
            assertThat(response.getSlotsCount()).isZero();
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("too many candidates is INVALID_ARGUMENT")
        void tooManyCandidates() {
            final RunAuctionRequest.Builder builder = RunAuctionRequest.newBuilder()
                    .setSlots(1)
                    .setReservePriceMicros(0L);
            for (int i = 0; i < MAX_CANDIDATES + 1; i++) {
                builder.addCandidates(candidate(i + 1L, 10_000L, QUALITY_ONE_BPS));
            }
            assertInvalid(builder.build());
        }

        @Test
        @DisplayName("slots above capacity is INVALID_ARGUMENT")
        void tooManySlots() {
            assertInvalid(RunAuctionRequest.newBuilder()
                    .setSlots(MAX_SLOTS + 1)
                    .setReservePriceMicros(0L)
                    .addCandidates(candidate(1L, 10_000L, QUALITY_ONE_BPS))
                    .build());
        }

        @Test
        @DisplayName("negative slots is INVALID_ARGUMENT")
        void negativeSlots() {
            assertInvalid(RunAuctionRequest.newBuilder()
                    .setSlots(-1)
                    .setReservePriceMicros(0L)
                    .build());
        }

        @Test
        @DisplayName("bid above the engine cap is INVALID_ARGUMENT")
        void bidOutOfRange() {
            assertInvalid(RunAuctionRequest.newBuilder()
                    .setSlots(1)
                    .setReservePriceMicros(0L)
                    .addCandidates(candidate(1L, io.bidflow.auction.AuctionRequest.MAX_BID_MICROS + 1, QUALITY_ONE_BPS))
                    .build());
        }

        @Test
        @DisplayName("quality of zero is INVALID_ARGUMENT")
        void qualityOutOfRange() {
            assertInvalid(RunAuctionRequest.newBuilder()
                    .setSlots(1)
                    .setReservePriceMicros(0L)
                    .addCandidates(candidate(1L, 10_000L, 0))
                    .build());
        }

        @Test
        @DisplayName("reserve above the bid cap is INVALID_ARGUMENT")
        void reserveOutOfRange() {
            assertInvalid(RunAuctionRequest.newBuilder()
                    .setSlots(1)
                    .setReservePriceMicros(io.bidflow.auction.AuctionRequest.MAX_BID_MICROS + 1)
                    .addCandidates(candidate(1L, 10_000L, QUALITY_ONE_BPS))
                    .build());
        }

        private void assertInvalid(RunAuctionRequest request) {
            assertThatThrownBy(() -> stub.runAuction(request))
                    .isInstanceOf(StatusRuntimeException.class)
                    .satisfies(t -> assertThat(((StatusRuntimeException) t).getStatus().getCode())
                            .isEqualTo(Status.Code.INVALID_ARGUMENT));
        }
    }

    private static Candidate candidate(long campaignId, long bidMicros, int qualityBps) {
        return Candidate.newBuilder()
                .setCampaignId(campaignId)
                .setBidMicros(bidMicros)
                .setQualityBps(qualityBps)
                .build();
    }
}
