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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves that {@code workers + queueDepth} is a hard ceiling on outstanding work: while
 * handlers are parked, further calls are shed rather than queued unboundedly.
 */
class BacklogBoundTest {

    private static final int WORKERS = 2;
    private static final int QUEUE_DEPTH = 1;
    private static final int PERMIT_BUDGET = WORKERS + QUEUE_DEPTH;

    private AuctionServer auctionServer;
    private ManagedChannel channel;
    private AuctionServiceGrpc.AuctionServiceStub asyncStub;
    private CountDownLatch release;
    private CountDownLatch workersEntered;

    @BeforeEach
    void setUp() throws Exception {
        release = new CountDownLatch(1);
        // Only the worker threads can park inside the handler; queued calls wait on the
        // executor and do not enter until a worker frees up.
        workersEntered = new CountDownLatch(WORKERS);
        auctionServer = new AuctionServer(0, WORKERS, QUEUE_DEPTH, 64, 8);
        auctionServer.service().blockBeforeRun(release, workersEntered);
        auctionServer.start();
        channel = ManagedChannelBuilder.forAddress("localhost", auctionServer.port())
                .usePlaintext()
                .build();
        asyncStub = AuctionServiceGrpc.newStub(channel);
    }

    @AfterEach
    void tearDown() throws Exception {
        release.countDown();
        channel.shutdownNow();
        channel.awaitTermination(5, TimeUnit.SECONDS);
        auctionServer.close();
    }

    @Test
    @DisplayName("permit budget is the maximum outstanding work while handlers are blocked")
    void workersPlusQueueDepthBoundsOutstandingWork() throws Exception {
        assertThat(auctionServer.admission().permitBudget()).isEqualTo(PERMIT_BUDGET);

        final List<AtomicReference<Object>> held = new ArrayList<>();
        for (int i = 0; i < PERMIT_BUDGET; i++) {
            final AtomicReference<Object> outcome = new AtomicReference<>();
            held.add(outcome);
            asyncStub.runAuction(simpleRequest(), recordingObserver(outcome));
        }
        assertThat(workersEntered.await(2, TimeUnit.SECONDS)).isTrue();

        final long admitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (auctionServer.admission().acquiredPermits() < PERMIT_BUDGET
                && System.nanoTime() < admitDeadline) {
            Thread.sleep(10);
        }
        assertThat(auctionServer.admission().acquiredPermits()).isEqualTo(PERMIT_BUDGET);

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
        assertThat(auctionServer.shedSaturated()).isEqualTo(1L);
        assertThat(auctionServer.admission().acquiredPermits()).isEqualTo(PERMIT_BUDGET);
        assertThat(shedOutcome.get()).isInstanceOf(StatusRuntimeException.class);
        assertThat(((StatusRuntimeException) shedOutcome.get()).getStatus().getCode())
                .isEqualTo(Status.Code.RESOURCE_EXHAUSTED);

        // Workers are still blocked; none of the held calls have responded yet.
        for (AtomicReference<Object> outcome : held) {
            assertThat(outcome.get()).isNull();
        }
        assertThat(auctionServer.served()).isZero();

        release.countDown();
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (auctionServer.served() < PERMIT_BUDGET && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(auctionServer.served()).isEqualTo(PERMIT_BUDGET);
        assertThat(auctionServer.admission().acquiredPermits()).isZero();
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
