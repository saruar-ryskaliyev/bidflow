package io.bidflow.bench;

import io.bidflow.auction.AuctionEngine;
import io.bidflow.auction.AuctionOutcome;
import io.bidflow.auction.AuctionRequest;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Cost of one full auction — eligibility, partial sort, campaign dedup, and GSP pricing —
 * on a warmed engine with every buffer pre-allocated.
 *
 * <p>The candidate set is generated once per trial from a fixed seed and the request is
 * never modified by {@code run}, so every invocation prices the identical auction. Ids are
 * drawn from a pool of twelve campaigns so the one-slot-per-campaign path is exercised,
 * and the reserve excludes a realistic fraction of candidates.
 *
 * <p>Run alongside {@code -prof gc}: the claim under test is not only the latency but
 * that {@code gc.alloc.rate.norm} is zero bytes per auction in steady state.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@State(Scope.Thread)
public class AuctionEngineBench {

    private static final int SLOTS = 3;
    private static final long RESERVE_MICROS = 5_000L;
    private static final int CAMPAIGN_POOL = 12;

    @Param({"8", "64"})
    int candidates;

    private AuctionEngine engine;
    private AuctionRequest request;
    private AuctionOutcome outcome;

    @Setup(Level.Trial)
    public void setUp() {
        engine = new AuctionEngine(candidates);
        request = new AuctionRequest(candidates);
        outcome = new AuctionOutcome(SLOTS);

        final Random random = new Random(42);
        request.reset(SLOTS, RESERVE_MICROS);
        for (int i = 0; i < candidates; i++) {
            request.add(
                    1 + random.nextInt(CAMPAIGN_POOL),
                    random.nextLong(1_000_000L + 1),
                    1 + random.nextInt(AuctionRequest.QUALITY_ONE_BPS));
        }
    }

    @Benchmark
    public long auction() {
        engine.run(request, outcome);
        // Consumes the result so the JIT cannot elide the pricing pass.
        return outcome.totalPriceMicros();
    }
}
