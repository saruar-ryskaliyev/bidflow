package io.bidflow.bench;

import io.bidflow.budget.Lease;
import io.bidflow.budget.SpendAuthority;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Cost of {@link SpendAuthority#tryReserve} — the budget check that sits on the serving
 * path of every request.
 *
 * <p>The lease is made effectively inexhaustible and is reinstalled each iteration, so
 * every invocation takes the successful-spend path: the two comparisons and the addition
 * the class documentation promises, with no allocation and no clock read (the caller
 * supplies the time).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@State(Scope.Thread)
public class SpendAuthorityBench {

    private SpendAuthority wallet;
    private long nextLeaseId = Lease.NONE;

    @Setup(Level.Iteration)
    public void setUp() {
        wallet = new SpendAuthority(0, 1L);
        wallet.installLease(new Lease(++nextLeaseId, Long.MAX_VALUE / 2, Long.MAX_VALUE), 0L);
    }

    @Benchmark
    public boolean tryReserve() {
        return wallet.tryReserve(1L, 100L);
    }
}
