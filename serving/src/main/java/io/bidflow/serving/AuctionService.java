package io.bidflow.serving;

import io.bidflow.auction.AuctionEngine;
import io.bidflow.auction.AuctionOutcome;
import io.bidflow.auction.AuctionRequest;
import io.bidflow.serving.v1.AuctionServiceGrpc;
import io.bidflow.serving.v1.Candidate;
import io.bidflow.serving.v1.ClickStatus;
import io.bidflow.serving.v1.RecordClickRequest;
import io.bidflow.serving.v1.RecordClickResponse;
import io.bidflow.serving.v1.RunAuctionRequest;
import io.bidflow.serving.v1.RunAuctionResponse;
import io.bidflow.serving.v1.Slot;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * gRPC handler for {@code RunAuction} and {@code RecordClick}.
 *
 * <p>Without a budget coordinator this is a pure auction transport. With one, each call is
 * routed to a {@link ServingShard} that filters unaffordable campaigns locally and records
 * clicks through a durable ledger — never blocking on the authority on the request path.
 */
public final class AuctionService extends AuctionServiceGrpc.AuctionServiceImplBase {

    private final int maxCandidates;
    private final int maxSlots;
    private final LongAdder shedExpired;
    private final LongAdder served;
    private final Executor workerExecutor;
    private final ThreadLocal<Buffers> buffers;
    private final CampaignBudgetCoordinator coordinator;
    private final ServingShard[] shards;
    private final AuctionReceiptStore receipts;
    private final AtomicInteger roundRobin = new AtomicInteger();

    private volatile CountDownLatch blockBeforeRun;
    private volatile CountDownLatch enteredBlock;

    /** Pure-auction constructor used by the existing serving tests and demos. */
    public AuctionService(
            int maxCandidates, int maxSlots, Executor workerExecutor, LongAdder shedExpired, LongAdder served) {
        this.maxCandidates = requirePositive(maxCandidates, "maxCandidates");
        this.maxSlots = requirePositive(maxSlots, "maxSlots");
        this.workerExecutor = workerExecutor;
        this.shedExpired = shedExpired;
        this.served = served;
        this.buffers = ThreadLocal.withInitial(() -> new Buffers(maxCandidates, maxSlots));
        this.coordinator = null;
        this.shards = null;
        this.receipts = null;
    }

    /** Budget-aware constructor: local wallets, paced grants, durable click charging. */
    public AuctionService(
            int maxCandidates,
            int maxSlots,
            Executor workerExecutor,
            LongAdder shedExpired,
            LongAdder served,
            CampaignBudgetCoordinator coordinator,
            AuctionReceiptStore receipts,
            Path ledgerRoot)
            throws IOException {
        this.maxCandidates = requirePositive(maxCandidates, "maxCandidates");
        this.maxSlots = requirePositive(maxSlots, "maxSlots");
        this.workerExecutor = workerExecutor;
        this.shedExpired = shedExpired;
        this.served = served;
        this.buffers = ThreadLocal.withInitial(() -> new Buffers(maxCandidates, maxSlots));
        this.coordinator = coordinator;
        this.receipts = receipts;
        this.shards = new ServingShard[coordinator.shardCount()];
        for (int i = 0; i < shards.length; i++) {
            shards[i] = new ServingShard(
                    i,
                    1L,
                    maxCandidates,
                    maxSlots,
                    coordinator,
                    receipts,
                    ledgerRoot.resolve("shard-" + i));
            shards[i].ensureLeases(System.nanoTime());
        }
    }

    void blockBeforeRun(CountDownLatch release) {
        blockBeforeRun(release, null);
    }

    void blockBeforeRun(CountDownLatch release, CountDownLatch entered) {
        this.blockBeforeRun = release;
        this.enteredBlock = release == null ? null : entered;
    }

    boolean budgetAware() {
        return shards != null;
    }

    CampaignBudgetCoordinator coordinator() {
        return coordinator;
    }

    ServingShard shard(int id) {
        return shards[id];
    }

    @Override
    public void runAuction(RunAuctionRequest request, StreamObserver<RunAuctionResponse> responseObserver) {
        final Runnable work = Context.current().wrap(() -> handleAuction(request, responseObserver));
        workerExecutor.execute(work);
    }

    @Override
    public void recordClick(RecordClickRequest request, StreamObserver<RecordClickResponse> responseObserver) {
        final Runnable work = Context.current().wrap(() -> handleClick(request, responseObserver));
        workerExecutor.execute(work);
    }

    private void handleAuction(RunAuctionRequest request, StreamObserver<RunAuctionResponse> responseObserver) {
        if (!awaitGate(responseObserver)) {
            return;
        }
        if (Context.current().isCancelled()) {
            shedExpired.increment();
            final Throwable cause = Context.current().cancellationCause();
            final Status status = cause == null
                    ? Status.DEADLINE_EXCEEDED.withDescription("deadline expired before auction")
                    : Status.fromThrowable(cause);
            responseObserver.onError(status.asRuntimeException());
            return;
        }

        final RunAuctionResponse response;
        try {
            response = shards == null ? runPure(request) : runBudgeted(request);
        } catch (IllegalArgumentException e) {
            responseObserver.onError(
                    Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
            return;
        }

        served.increment();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    private void handleClick(RecordClickRequest request, StreamObserver<RecordClickResponse> responseObserver) {
        if (shards == null) {
            responseObserver.onError(Status.UNIMPLEMENTED
                    .withDescription("RecordClick requires budget-aware serving")
                    .asRuntimeException());
            return;
        }
        if (Context.current().isCancelled()) {
            shedExpired.increment();
            responseObserver.onError(Status.DEADLINE_EXCEEDED
                    .withDescription("deadline expired before click")
                    .asRuntimeException());
            return;
        }
        try {
            final long now = System.nanoTime();
            final AuctionReceiptStore.Receipt receipt = receipts.get(request.getAuctionToken(), now);
            if (receipt == null) {
                responseObserver.onNext(RecordClickResponse.newBuilder()
                        .setStatus(ClickStatus.CLICK_INVALID)
                        .build());
                responseObserver.onCompleted();
                return;
            }
            final RecordClickResponse response = shards[receipt.shardId()].recordClick(request, now);
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (IOException e) {
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    private RunAuctionResponse runBudgeted(RunAuctionRequest proto) {
        final int idx = Math.floorMod(roundRobin.getAndIncrement(), shards.length);
        return shards[idx].runAuction(proto, System.nanoTime());
    }

    RunAuctionResponse run(RunAuctionRequest proto) {
        return runPure(proto);
    }

    private RunAuctionResponse runPure(RunAuctionRequest proto) {
        if (proto.getSlots() < 0 || proto.getSlots() > maxSlots) {
            throw new IllegalArgumentException(
                    "slots must be in [0, " + maxSlots + "], was " + proto.getSlots());
        }
        if (proto.getCandidatesCount() > maxCandidates) {
            throw new IllegalArgumentException(
                    "request holds " + proto.getCandidatesCount()
                            + " candidates, engine sized for " + maxCandidates);
        }
        if (proto.getReservePriceMicros() < 0
                || proto.getReservePriceMicros() > AuctionRequest.MAX_BID_MICROS) {
            throw new IllegalArgumentException(
                    "reservePriceMicros must be in [0, " + AuctionRequest.MAX_BID_MICROS
                            + "], was " + proto.getReservePriceMicros());
        }

        final Buffers b = buffers.get();
        b.request.reset(proto.getSlots(), proto.getReservePriceMicros());
        for (int i = 0; i < proto.getCandidatesCount(); i++) {
            final Candidate c = proto.getCandidates(i);
            b.request.add(c.getCampaignId(), c.getBidMicros(), c.getQualityBps());
        }
        b.engine.run(b.request, b.outcome);

        final RunAuctionResponse.Builder builder = RunAuctionResponse.newBuilder();
        for (int k = 0; k < b.outcome.size(); k++) {
            builder.addSlots(Slot.newBuilder()
                    .setCampaignId(b.outcome.campaignId(k))
                    .setPriceMicros(b.outcome.priceMicros(k))
                    .setAdRank(b.outcome.adRank(k))
                    .setQualityBps(b.outcome.qualityBps(k))
                    .build());
        }
        return builder.build();
    }

    private boolean awaitGate(StreamObserver<?> responseObserver) {
        final CountDownLatch gate = blockBeforeRun;
        if (gate == null) {
            return true;
        }
        final CountDownLatch entered = enteredBlock;
        if (entered != null) {
            entered.countDown();
        }
        try {
            gate.await();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            responseObserver.onError(
                    Status.CANCELLED.withDescription("interrupted while blocked").asRuntimeException());
            return false;
        }
    }

    void closeShards() {
        if (shards == null) {
            return;
        }
        for (ServingShard shard : shards) {
            try {
                shard.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive, was " + value);
        }
        return value;
    }

    private static final class Buffers {
        final AuctionEngine engine;
        final AuctionRequest request;
        final AuctionOutcome outcome;

        Buffers(int maxCandidates, int maxSlots) {
            this.engine = new AuctionEngine(maxCandidates);
            this.request = new AuctionRequest(maxCandidates);
            this.outcome = new AuctionOutcome(maxSlots);
        }
    }
}
