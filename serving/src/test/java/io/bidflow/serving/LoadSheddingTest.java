package io.bidflow.serving;

import static io.bidflow.auction.AuctionRequest.QUALITY_ONE_BPS;
import static org.assertj.core.api.Assertions.assertThat;

import io.bidflow.serving.v1.AuctionServiceGrpc;
import io.bidflow.serving.v1.Candidate;
import io.bidflow.serving.v1.RunAuctionRequest;
import io.bidflow.serving.v1.RunAuctionResponse;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * When the inflight budget is exhausted, excess calls are closed with
 * {@code RESOURCE_EXHAUSTED} rather than queued — shedding beats queueing.
 */
class LoadSheddingTest {

    private AuctionServer auctionServer;
    private ManagedChannel channel;
    private AuctionServiceGrpc.AuctionServiceStub asyncStub;
    private AuctionServiceGrpc.AuctionServiceBlockingStub blockingStub;
    private CountDownLatch release;
    private CountDownLatch entered;

    @BeforeEach
    void setUp() throws Exception {
        // One worker, no queue: the permit budget is exactly one in-flight call.
        release = new CountDownLatch(1);
        entered = new CountDownLatch(1);
        auctionServer = new AuctionServer(0, 1, 0, 64, 8);
        auctionServer.service().blockBeforeRun(release, entered);
        auctionServer.start();
        channel = ManagedChannelBuilder.forAddress("localhost", auctionServer.port())
                .usePlaintext()
                .build();
        asyncStub = AuctionServiceGrpc.newStub(channel);
        blockingStub = AuctionServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void tearDown() throws Exception {
        release.countDown();
        channel.shutdownNow();
        channel.awaitTermination(5, TimeUnit.SECONDS);
        auctionServer.close();
    }

    @Test
    @DisplayName("calls beyond the permit budget fail fast with RESOURCE_EXHAUSTED")
    void shedsWhenSaturated() throws Exception {
        assertThat(auctionServer.admission().permitBudget()).isEqualTo(1);

        final AtomicReference<Object> heldOutcome = new AtomicReference<>();
        asyncStub.runAuction(simpleRequest(), recordingObserver(heldOutcome));
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(auctionServer.admission().acquiredPermits()).isEqualTo(1);

        final AtomicReference<Object> shedOutcome = new AtomicReference<>();
        final CountDownLatch shedDone = new CountDownLatch(1);
        asyncStub.runAuction(simpleRequest(), new StreamObserver<>() {
            @Override
            public void onNext(RunAuctionResponse value) {
                shedOutcome.set(value);
            }

            @Override
            public void onError(Throwable t) {
                shedOutcome.set(t);
                shedDone.countDown();
            }

            @Override
            public void onCompleted() {
                shedDone.countDown();
            }
        });

        assertThat(shedDone.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(auctionServer.shedSaturated()).isGreaterThan(0L);
        assertThat(shedOutcome.get()).isInstanceOf(StatusRuntimeException.class);
        assertThat(((StatusRuntimeException) shedOutcome.get()).getStatus().getCode())
                .isEqualTo(Status.Code.RESOURCE_EXHAUSTED);

        release.countDown();
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (auctionServer.served() < 1 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(auctionServer.served()).isEqualTo(1L);
        assertThat(heldOutcome.get()).isInstanceOf(RunAuctionResponse.class);

        auctionServer.service().blockBeforeRun(null);
        final RunAuctionResponse recovered = blockingStub.runAuction(simpleRequest());
        assertThat(recovered.getSlotsCount()).isEqualTo(1);
        assertThat(auctionServer.served()).isEqualTo(2L);
    }

    private static StreamObserver<RunAuctionResponse> recordingObserver(AtomicReference<Object> outcome) {
        return new StreamObserver<>() {
            @Override
            public void onNext(RunAuctionResponse value) {
                outcome.set(value);
            }

            @Override
            public void onError(Throwable t) {
                outcome.set(t);
            }

            @Override
            public void onCompleted() {}
        };
    }

    private static RunAuctionRequest simpleRequest() {
        return RunAuctionRequest.newBuilder()
                .setSlots(1)
                .setReservePriceMicros(0L)
                .addCandidates(Candidate.newBuilder()
                        .setCampaignId(1L)
                        .setBidMicros(10_000L)
                        .setQualityBps(QUALITY_ONE_BPS)
                        .build())
                .build();
    }
}
