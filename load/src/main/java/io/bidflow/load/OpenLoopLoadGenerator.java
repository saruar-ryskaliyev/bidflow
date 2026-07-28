package io.bidflow.load;

import io.bidflow.serving.v1.AuctionServiceGrpc;
import io.bidflow.serving.v1.Candidate;
import io.bidflow.serving.v1.RunAuctionRequest;
import io.bidflow.serving.v1.RunAuctionResponse;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import org.HdrHistogram.Histogram;

/**
 * Open-loop load generator against {@code RunAuction}.
 *
 * <p>Arrivals are scheduled at a fixed intended RPS. Completion latency is measured from
 * the intended start, not from send time, so client/server stalls count as long samples.
 *
 * <p>Usage:
 * <pre>
 *   ./gradlew :serving:run &
 *   ./gradlew :load:run --args="--host localhost --port 50051 --rps 2000 --duration 60 --candidates 64"
 * </pre>
 */
public final class OpenLoopLoadGenerator {

    public static void main(String[] args) throws Exception {
        final Config config = Config.parse(args);
        final ManagedChannel channel = ManagedChannelBuilder.forAddress(config.host, config.port)
                .usePlaintext()
                .build();
        try {
            final Result result = run(channel, config);
            System.out.print(IntendedStartLatencyRecorder.formatReport(result.histogram(), "auction latency"));
            System.out.printf(Locale.ROOT,
                    "throughput %.1f rps  deadline=%d saturated=%d errors=%d%n",
                    result.throughputRps(),
                    result.deadlineExceeded(),
                    result.resourceExhausted(),
                    result.otherErrors());
            if (config.out != null) {
                Files.createDirectories(config.out.getParent() == null ? Path.of(".") : config.out.getParent());
                final String json = "{\"latency\":" + IntendedStartLatencyRecorder.toJson(result.histogram())
                        + ",\"throughputRps\":" + result.throughputRps()
                        + ",\"deadlineExceeded\":" + result.deadlineExceeded()
                        + ",\"resourceExhausted\":" + result.resourceExhausted()
                        + ",\"otherErrors\":" + result.otherErrors()
                        + "}";
                Files.writeString(config.out, json, StandardCharsets.UTF_8);
                System.out.println("wrote " + config.out);
            }
        } finally {
            channel.shutdownNow();
            channel.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    static Result run(ManagedChannel channel, Config config) throws InterruptedException {
        final AuctionServiceGrpc.AuctionServiceStub stub = AuctionServiceGrpc.newStub(channel);
        final IntendedStartLatencyRecorder latency =
                new IntendedStartLatencyRecorder(TimeUnit.SECONDS.toNanos(30));
        final LongAdder deadlineExceeded = new LongAdder();
        final LongAdder resourceExhausted = new LongAdder();
        final LongAdder otherErrors = new LongAdder();
        final LongAdder completed = new LongAdder();

        final long intervalNanos = TimeUnit.SECONDS.toNanos(1) / Math.max(1, config.rps);
        final long warmupNanos = TimeUnit.SECONDS.toNanos(config.warmupSeconds);
        final long durationNanos = TimeUnit.SECONDS.toNanos(config.durationSeconds);
        final long start = System.nanoTime();
        final long warmupEnd = start + warmupNanos;
        final long end = warmupEnd + durationNanos;
        final RunAuctionRequest request = buildRequest(config.candidates, config.slots, config.seed);
        final LongAdder outstanding = new LongAdder();

        long next = start;
        while (System.nanoTime() < end) {
            final long now = System.nanoTime();
            if (now < next) {
                parkNanos(next - now);
            }
            final long intended = next;
            next += intervalNanos;
            final boolean measuring = intended >= warmupEnd;
            outstanding.increment();
            stub.withDeadlineAfter(config.deadlineMillis, TimeUnit.MILLISECONDS)
                    .runAuction(request, new StreamObserver<>() {
                        @Override
                        public void onNext(RunAuctionResponse value) {}

                        @Override
                        public void onError(Throwable t) {
                            if (measuring) {
                                latency.recordCompletion(intended);
                                if (t instanceof StatusRuntimeException sre) {
                                    switch (sre.getStatus().getCode()) {
                                        case DEADLINE_EXCEEDED -> deadlineExceeded.increment();
                                        case RESOURCE_EXHAUSTED -> resourceExhausted.increment();
                                        default -> otherErrors.increment();
                                    }
                                } else {
                                    otherErrors.increment();
                                }
                            }
                            outstanding.decrement();
                        }

                        @Override
                        public void onCompleted() {
                            if (measuring) {
                                latency.recordCompletion(intended);
                                completed.increment();
                            }
                            outstanding.decrement();
                        }
                    });
        }

        final long drainDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (outstanding.sum() > 0 && System.nanoTime() < drainDeadline) {
            Thread.sleep(10);
        }

        final Histogram histogram = latency.snapshot();
        final double throughput = completed.sum() / (double) Math.max(1, config.durationSeconds);
        return new Result(
                histogram,
                throughput,
                deadlineExceeded.sum(),
                resourceExhausted.sum(),
                otherErrors.sum());
    }

    private static void parkNanos(long nanos) {
        if (nanos > 0) {
            java.util.concurrent.locks.LockSupport.parkNanos(nanos);
        }
    }

    static RunAuctionRequest buildRequest(int candidates, int slots, long seed) {
        final Random random = new Random(seed);
        final RunAuctionRequest.Builder builder = RunAuctionRequest.newBuilder()
                .setSlots(slots)
                .setReservePriceMicros(5_000L);
        for (int i = 0; i < candidates; i++) {
            builder.addCandidates(Candidate.newBuilder()
                    .setCampaignId(1 + random.nextInt(12))
                    .setBidMicros(random.nextLong(1_000_000L + 1))
                    .setQualityBps(1 + random.nextInt(10_000))
                    .build());
        }
        return builder.build();
    }

    record Result(
            Histogram histogram,
            double throughputRps,
            long deadlineExceeded,
            long resourceExhausted,
            long otherErrors) {}

    static final class Config {
        String host = "localhost";
        int port = 50051;
        int rps = 1000;
        int durationSeconds = 10;
        int warmupSeconds = 2;
        int candidates = 64;
        int slots = 3;
        int deadlineMillis = 50;
        long seed = 42;
        Path out = Path.of("load/build/results/latest.json");

        static Config parse(String[] args) {
            final Config c = new Config();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--host" -> c.host = args[++i];
                    case "--port" -> c.port = Integer.parseInt(args[++i]);
                    case "--rps" -> c.rps = Integer.parseInt(args[++i]);
                    case "--duration" -> c.durationSeconds = Integer.parseInt(args[++i]);
                    case "--warmup" -> c.warmupSeconds = Integer.parseInt(args[++i]);
                    case "--candidates" -> c.candidates = Integer.parseInt(args[++i]);
                    case "--slots" -> c.slots = Integer.parseInt(args[++i]);
                    case "--deadline-ms" -> c.deadlineMillis = Integer.parseInt(args[++i]);
                    case "--seed" -> c.seed = Long.parseLong(args[++i]);
                    case "--out" -> c.out = Path.of(args[++i]);
                    default -> throw new IllegalArgumentException("unknown arg: " + args[i]);
                }
            }
            return c;
        }
    }
}
