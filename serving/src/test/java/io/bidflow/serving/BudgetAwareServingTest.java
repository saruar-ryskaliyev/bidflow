package io.bidflow.serving;

import static io.bidflow.auction.AuctionRequest.QUALITY_ONE_BPS;
import static org.assertj.core.api.Assertions.assertThat;

import io.bidflow.budget.BudgetAuthority;
import io.bidflow.serving.v1.AuctionServiceGrpc;
import io.bidflow.serving.v1.Candidate;
import io.bidflow.serving.v1.ClickStatus;
import io.bidflow.serving.v1.RecordClickRequest;
import io.bidflow.serving.v1.RecordClickResponse;
import io.bidflow.serving.v1.RunAuctionRequest;
import io.bidflow.serving.v1.RunAuctionResponse;
import io.grpc.ManagedChannel;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end budget-aware serving: auction → click charges the GSP price once, and a
 * duplicate key replays without spending again.
 */
class BudgetAwareServingTest {

    private static final long LEASE_DURATION = 30_000_000_000L;

    @TempDir
    Path temp;

    private String serverName;
    private AuctionServer auctionServer;
    private ManagedChannel channel;
    private AuctionServiceGrpc.AuctionServiceBlockingStub stub;

    @BeforeEach
    void setUp() throws Exception {
        serverName = InProcessServerBuilder.generateName();
        auctionServer = AuctionServer.inProcessBudgeted(
                InProcessServerBuilder.forName(serverName),
                2,
                8,
                64,
                8,
                List.of(new CampaignBudgetConfig(
                        1L, 100_000L, 1_000_000L, 200_000L, LEASE_DURATION, BudgetAuthority.NEVER_RECLAIM),
                        new CampaignBudgetConfig(
                                2L, 80_000L, 1_000_000L, 200_000L, LEASE_DURATION, BudgetAuthority.NEVER_RECLAIM),
                        new CampaignBudgetConfig(
                                3L, 60_000L, 1_000_000L, 200_000L, LEASE_DURATION, BudgetAuthority.NEVER_RECLAIM),
                        new CampaignBudgetConfig(
                                4L, 40_000L, 1_000_000L, 200_000L, LEASE_DURATION, BudgetAuthority.NEVER_RECLAIM)),
                temp);
        auctionServer.start();
        channel = InProcessChannelBuilder.forName(serverName).build();
        stub = AuctionServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void tearDown() throws Exception {
        channel.shutdownNow();
        channel.awaitTermination(5, TimeUnit.SECONDS);
        auctionServer.close();
    }

    @Test
    @DisplayName("auction then click charges the GSP price once; duplicate key replays")
    void clickChargesOnce() {
        final RunAuctionResponse auction = stub.runAuction(RunAuctionRequest.newBuilder()
                .setSlots(3)
                .setReservePriceMicros(5_000L)
                .addCandidates(candidate(1L, 100_000L))
                .addCandidates(candidate(2L, 80_000L))
                .addCandidates(candidate(3L, 60_000L))
                .addCandidates(candidate(4L, 40_000L))
                .build());

        assertThat(auction.getAuctionToken()).isNotBlank();
        assertThat(auction.getSlotsCount()).isEqualTo(3);
        assertThat(auction.getSlots(0).getPriceMicros()).isEqualTo(80_000L);

        final RecordClickResponse charged = stub.recordClick(RecordClickRequest.newBuilder()
                .setAuctionToken(auction.getAuctionToken())
                .setSlot(0)
                .setIdempotencyKey("click-1")
                .build());
        assertThat(charged.getStatus()).isEqualTo(ClickStatus.CLICK_CHARGED);
        assertThat(charged.getChargedMicros()).isEqualTo(80_000L);
        assertThat(charged.getCampaignId()).isEqualTo(1L);

        final RecordClickResponse replay = stub.recordClick(RecordClickRequest.newBuilder()
                .setAuctionToken(auction.getAuctionToken())
                .setSlot(0)
                .setIdempotencyKey("click-1")
                .build());
        assertThat(replay.getStatus()).isEqualTo(ClickStatus.CLICK_REPLAYED);
        assertThat(replay.getChargedMicros()).isEqualTo(80_000L);
    }

    @Test
    @DisplayName("forged or unknown auction tokens are rejected")
    void forgedTokenIsInvalid() {
        final RecordClickResponse response = stub.recordClick(RecordClickRequest.newBuilder()
                .setAuctionToken("nope")
                .setSlot(0)
                .setIdempotencyKey("x")
                .build());
        assertThat(response.getStatus()).isEqualTo(ClickStatus.CLICK_INVALID);
    }

    private static Candidate candidate(long id, long bid) {
        return Candidate.newBuilder()
                .setCampaignId(id)
                .setBidMicros(bid)
                .setQualityBps(QUALITY_ONE_BPS)
                .build();
    }
}
