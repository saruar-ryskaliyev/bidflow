package io.bidflow.serving;

import io.bidflow.budget.LeaseGrantPolicy;
import io.bidflow.budget.UnpacedGrantPolicy;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Bootstraps the auction gRPC server.
 *
 * <p>A fixed pool of platform threads — not virtual threads — so each worker can own a
 * {@link ThreadLocal} engine and reuse its buffers across requests. The gRPC transport
 * keeps its own executor for call lifecycle; the auction hops onto the worker pool inside
 * {@link AuctionService}, so a parked auction cannot stall admission. Admission control
 * sits in front of that pool and sheds when saturated; see {@link AdmissionControl}.
 *
 * <p>Port {@code 0} lets the OS pick a free port, which the tests use.
 */
public final class AuctionServer implements AutoCloseable {

    public static final int DEFAULT_PORT = 50051;
    public static final int DEFAULT_WORKERS = 4;
    public static final int DEFAULT_QUEUE_DEPTH = 64;
    public static final int DEFAULT_MAX_CANDIDATES = 64;
    public static final int DEFAULT_MAX_SLOTS = 8;
    public static final long DEFAULT_RECEIPT_TTL_NANOS = 60_000_000_000L;

    private final LongAdder served = new LongAdder();
    private final LongAdder shedSaturated = new LongAdder();
    private final LongAdder shedExpired = new LongAdder();

    private final int workers;
    private final int queueDepth;
    private final int maxCandidates;
    private final int maxSlots;
    private final AdmissionControl admission;
    private final AuctionService service;
    private final ExecutorService executor;
    private final Server server;

    /** Pure-auction server. */
    public AuctionServer(int port, int workers, int queueDepth, int maxCandidates, int maxSlots) {
        if (port < 0) {
            throw new IllegalArgumentException("port must not be negative, was " + port);
        }
        this.workers = workers;
        this.queueDepth = queueDepth;
        this.maxCandidates = maxCandidates;
        this.maxSlots = maxSlots;
        this.admission = new AdmissionControl(workers, queueDepth, shedSaturated);
        this.executor = Executors.newFixedThreadPool(workers, namedThreads("auction-worker"));
        this.service = new AuctionService(maxCandidates, maxSlots, executor, shedExpired, served);
        this.server = NettyServerBuilder.forPort(port)
                .intercept(admission)
                .addService(service)
                .build();
    }

    public AuctionServer(int port) {
        this(port, DEFAULT_WORKERS, DEFAULT_QUEUE_DEPTH, DEFAULT_MAX_CANDIDATES, DEFAULT_MAX_SLOTS);
    }

    /** Budget-aware server with paced leases and durable click charging. */
    public AuctionServer(
            int port,
            int workers,
            int queueDepth,
            int maxCandidates,
            int maxSlots,
            Collection<CampaignBudgetConfig> campaigns,
            LeaseGrantPolicy grantPolicy,
            Path ledgerRoot)
            throws IOException {
        if (port < 0) {
            throw new IllegalArgumentException("port must not be negative, was " + port);
        }
        this.workers = workers;
        this.queueDepth = queueDepth;
        this.maxCandidates = maxCandidates;
        this.maxSlots = maxSlots;
        this.admission = new AdmissionControl(workers, queueDepth, shedSaturated);
        this.executor = Executors.newFixedThreadPool(workers, namedThreads("auction-worker"));
        Files.createDirectories(ledgerRoot);
        final CampaignBudgetCoordinator coordinator =
                new CampaignBudgetCoordinator(workers, campaigns, grantPolicy);
        final AuctionReceiptStore receipts = new AuctionReceiptStore(10_000, DEFAULT_RECEIPT_TTL_NANOS);
        this.service = new AuctionService(
                maxCandidates, maxSlots, executor, shedExpired, served, coordinator, receipts, ledgerRoot);
        this.server = NettyServerBuilder.forPort(port)
                .intercept(admission)
                .addService(service)
                .build();
    }

    public static AuctionServer inProcess(
            ServerBuilder<?> builder, int workers, int queueDepth, int maxCandidates, int maxSlots) {
        return new AuctionServer(builder, workers, queueDepth, maxCandidates, maxSlots);
    }

    public static AuctionServer inProcessBudgeted(
            ServerBuilder<?> builder,
            int workers,
            int queueDepth,
            int maxCandidates,
            int maxSlots,
            Collection<CampaignBudgetConfig> campaigns,
            Path ledgerRoot)
            throws IOException {
        return new AuctionServer(
                builder, workers, queueDepth, maxCandidates, maxSlots, campaigns, UnpacedGrantPolicy.INSTANCE, ledgerRoot);
    }

    private AuctionServer(
            ServerBuilder<?> builder, int workers, int queueDepth, int maxCandidates, int maxSlots) {
        this.workers = workers;
        this.queueDepth = queueDepth;
        this.maxCandidates = maxCandidates;
        this.maxSlots = maxSlots;
        this.admission = new AdmissionControl(workers, queueDepth, shedSaturated);
        this.executor = Executors.newFixedThreadPool(workers, namedThreads("auction-worker"));
        this.service = new AuctionService(maxCandidates, maxSlots, executor, shedExpired, served);
        this.server = builder.intercept(admission).addService(service).build();
    }

    private AuctionServer(
            ServerBuilder<?> builder,
            int workers,
            int queueDepth,
            int maxCandidates,
            int maxSlots,
            Collection<CampaignBudgetConfig> campaigns,
            LeaseGrantPolicy grantPolicy,
            Path ledgerRoot)
            throws IOException {
        this.workers = workers;
        this.queueDepth = queueDepth;
        this.maxCandidates = maxCandidates;
        this.maxSlots = maxSlots;
        this.admission = new AdmissionControl(workers, queueDepth, shedSaturated);
        this.executor = Executors.newFixedThreadPool(workers, namedThreads("auction-worker"));
        Files.createDirectories(ledgerRoot);
        final CampaignBudgetCoordinator coordinator =
                new CampaignBudgetCoordinator(workers, campaigns, grantPolicy);
        final AuctionReceiptStore receipts = new AuctionReceiptStore(10_000, DEFAULT_RECEIPT_TTL_NANOS);
        this.service = new AuctionService(
                maxCandidates, maxSlots, executor, shedExpired, served, coordinator, receipts, ledgerRoot);
        this.server = builder.intercept(admission).addService(service).build();
    }

    public void start() throws IOException {
        server.start();
    }

    public int port() {
        return server.getPort();
    }

    public long served() {
        return served.sum();
    }

    public long shedSaturated() {
        return shedSaturated.sum();
    }

    public long shedExpired() {
        return shedExpired.sum();
    }

    public int workers() {
        return workers;
    }

    public int queueDepth() {
        return queueDepth;
    }

    public int maxCandidates() {
        return maxCandidates;
    }

    public int maxSlots() {
        return maxSlots;
    }

    public AdmissionControl admission() {
        return admission;
    }

    public AuctionService service() {
        return service;
    }

    @Override
    public void close() {
        server.shutdown();
        try {
            if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
                server.shutdownNow();
                server.awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            server.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            try {
                service.closeShards();
            } catch (UncheckedIOException ignored) {
                // Best-effort on shutdown.
            }
            executor.shutdownNow();
        }
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        final int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        final AuctionServer auctionServer;
        if (args.length > 1 && "budget".equals(args[1])) {
            final Path ledger = Path.of(args.length > 2 ? args[2] : "build/ledger");
            auctionServer = new AuctionServer(
                    port,
                    DEFAULT_WORKERS,
                    DEFAULT_QUEUE_DEPTH,
                    DEFAULT_MAX_CANDIDATES,
                    DEFAULT_MAX_SLOTS,
                    List.of(
                            new CampaignBudgetConfig(
                                    1L, 100_000L, 5_000_000L, 200_000L, 5_000_000_000L,
                                    io.bidflow.budget.BudgetAuthority.NEVER_RECLAIM),
                            new CampaignBudgetConfig(
                                    2L, 80_000L, 5_000_000L, 200_000L, 5_000_000_000L,
                                    io.bidflow.budget.BudgetAuthority.NEVER_RECLAIM)),
                    UnpacedGrantPolicy.INSTANCE,
                    ledger);
            System.out.println("bidflow budget-aware gRPC listening on :" + port);
        } else {
            auctionServer = new AuctionServer(port);
            System.out.println("bidflow auction gRPC listening on :" + port);
        }
        auctionServer.start();
        Runtime.getRuntime().addShutdownHook(new Thread(auctionServer::close, "auction-shutdown"));
        Thread.currentThread().join();
    }

    private static ThreadFactory namedThreads(String prefix) {
        final AtomicInteger n = new AtomicInteger();
        return runnable -> {
            final Thread t = new Thread(runnable, prefix + "-" + n.incrementAndGet());
            t.setDaemon(false);
            return t;
        };
    }
}
