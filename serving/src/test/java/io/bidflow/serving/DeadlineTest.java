package io.bidflow.serving;

import static io.bidflow.auction.AuctionRequest.QUALITY_ONE_BPS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.bidflow.serving.v1.AuctionServiceGrpc;
import io.bidflow.serving.v1.Candidate;
import io.bidflow.serving.v1.RunAuctionRequest;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A late ad response is worth nothing: when the client's deadline expires before the
 * engine runs, the handler skips it and {@link AuctionServer#shedExpired()} rises.
 *
 * <p>Uses Netty so the client deadline can fire while the worker is parked, independent
 * of the in-process transport's thread sharing.
 */
class DeadlineTest {

    private AuctionServer auctionServer;
    private ManagedChannel channel;
    private AuctionServiceGrpc.AuctionServiceBlockingStub stub;
    private CountDownLatch release;
    private CountDownLatch entered;

    @BeforeEach
    void setUp() throws Exception {
        release = new CountDownLatch(1);
        entered = new CountDownLatch(1);
        auctionServer = new AuctionServer(0, 1, 8, 64, 8);
        auctionServer.service().blockBeforeRun(release, entered);
        auctionServer.start();
        channel = ManagedChannelBuilder.forAddress("localhost", auctionServer.port())
                .usePlaintext()
                .build();
        stub = AuctionServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void tearDown() throws Exception {
        release.countDown();
        channel.shutdownNow();
        channel.awaitTermination(5, TimeUnit.SECONDS);
        auctionServer.close();
    }

    @Test
    @DisplayName("expired deadline skips the engine and increments shedExpired")
    void expiredDeadlineSkipsEngine() throws Exception {
        final ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            final Future<?> call = pool.submit(() -> stub
                    .withDeadlineAfter(80, TimeUnit.MILLISECONDS)
                    .runAuction(simpleRequest()));

            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            // Handler is parked on the gate; let the client deadline expire.
            Thread.sleep(150);
            release.countDown();

            assertThatThrownBy(() -> call.get(2, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(StatusRuntimeException.class)
                    .satisfies(t -> {
                        final Status.Code code =
                                ((StatusRuntimeException) t.getCause()).getStatus().getCode();
                        assertThat(code)
                                .isIn(Status.Code.DEADLINE_EXCEEDED, Status.Code.CANCELLED);
                    });

            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (auctionServer.shedExpired() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(auctionServer.shedExpired()).isEqualTo(1L);
            assertThat(auctionServer.served()).isZero();
        } finally {
            pool.shutdownNow();
        }
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
