package io.bidflow.serving;

import static io.bidflow.auction.AuctionRequest.QUALITY_ONE_BPS;
import static org.assertj.core.api.Assertions.assertThat;

import io.bidflow.serving.v1.AuctionServiceGrpc;
import io.bidflow.serving.v1.Candidate;
import io.bidflow.serving.v1.RunAuctionRequest;
import io.bidflow.serving.v1.RunAuctionResponse;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * One real Netty round trip on an OS-picked port, then a clean shutdown.
 */
class AuctionServerTest {

    @Test
    void nettyRoundTripAndShutdown() throws Exception {
        try (AuctionServer server = new AuctionServer(0, 2, 8, 64, 8)) {
            server.start();
            assertThat(server.port()).isPositive();

            final ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", server.port())
                    .usePlaintext()
                    .build();
            try {
                final AuctionServiceGrpc.AuctionServiceBlockingStub stub =
                        AuctionServiceGrpc.newBlockingStub(channel);
                final RunAuctionResponse response = stub.runAuction(RunAuctionRequest.newBuilder()
                        .setSlots(1)
                        .setReservePriceMicros(0L)
                        .addCandidates(Candidate.newBuilder()
                                .setCampaignId(7L)
                                .setBidMicros(25_000L)
                                .setQualityBps(QUALITY_ONE_BPS)
                                .build())
                        .build());
                assertThat(response.getSlotsCount()).isEqualTo(1);
                assertThat(response.getSlots(0).getCampaignId()).isEqualTo(7L);
                assertThat(response.getSlots(0).getPriceMicros()).isEqualTo(0L);
                assertThat(server.served()).isEqualTo(1L);
            } finally {
                channel.shutdownNow();
                channel.awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }
}
