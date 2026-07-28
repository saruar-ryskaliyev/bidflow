package io.bidflow.serving;

import io.grpc.ForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.LongAdder;

/**
 * Sheds excess work at the door rather than queueing it.
 *
 * <p>A late ad response is worth nothing, so when the inflight budget is exhausted the call
 * is closed with {@code RESOURCE_EXHAUSTED} from the transport thread — never parked on a
 * worker queue. The semaphore's permits equal {@code workers + queueDepth}; the worker pool
 * itself uses an unbounded queue whose effective depth this bound enforces. Rejecting inside
 * the executor would break calls mid-flight: grpc-java dispatches several callbacks per call
 * through the same executor.
 *
 * <p>A permit is held from admission until the response is closed or the call is cancelled —
 * not merely until the request stream half-closes — so work still executing on a worker
 * continues to count against the budget.
 */
public final class AdmissionControl implements ServerInterceptor {

    private final Semaphore inflight;
    private final LongAdder shedSaturated;
    private final LongAdder admitted;
    private final int permitBudget;

    /**
     * @param workers number of dedicated worker threads
     * @param queueDepth how many calls may wait behind those workers
     * @param shedSaturated shared counter for admission rejects
     */
    public AdmissionControl(int workers, int queueDepth, LongAdder shedSaturated) {
        if (workers <= 0) {
            throw new IllegalArgumentException("workers must be positive, was " + workers);
        }
        if (queueDepth < 0) {
            throw new IllegalArgumentException("queueDepth must not be negative, was " + queueDepth);
        }
        this.permitBudget = workers + queueDepth;
        this.inflight = new Semaphore(permitBudget, true);
        this.shedSaturated = shedSaturated;
        this.admitted = new LongAdder();
    }

    /** Total inflight permits — workers plus queue depth. */
    public int permitBudget() {
        return permitBudget;
    }

    /** How many calls currently hold a permit. Visible for tests. */
    public int acquiredPermits() {
        return permitBudget - inflight.availablePermits();
    }

    /** Cumulative successful admissions. Visible for tests. */
    public long admitted() {
        return admitted.sum();
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        if (!inflight.tryAcquire()) {
            shedSaturated.increment();
            call.close(
                    Status.RESOURCE_EXHAUSTED.withDescription("server saturated; shedding load"),
                    new Metadata());
            return new ServerCall.Listener<>() {};
        }
        admitted.increment();

        final PermitRelease release = new PermitRelease(inflight);
        final ServerCall<ReqT, RespT> guarded = new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
            @Override
            public void close(Status status, Metadata trailers) {
                try {
                    super.close(status, trailers);
                } finally {
                    release.releaseOnce();
                }
            }
        };

        final ServerCall.Listener<ReqT> delegate = next.startCall(guarded, headers);
        return new ServerCall.Listener<>() {
            @Override
            public void onMessage(ReqT message) {
                delegate.onMessage(message);
            }

            @Override
            public void onHalfClose() {
                delegate.onHalfClose();
            }

            @Override
            public void onCancel() {
                try {
                    delegate.onCancel();
                } finally {
                    // Cancellation may close the call without invoking ServerCall.close on
                    // every path; releasing here keeps the semaphore honest either way.
                    release.releaseOnce();
                }
            }

            @Override
            public void onComplete() {
                try {
                    delegate.onComplete();
                } finally {
                    release.releaseOnce();
                }
            }

            @Override
            public void onReady() {
                delegate.onReady();
            }
        };
    }

    /** Idempotent release so close and cancel/complete cannot double-return a permit. */
    private static final class PermitRelease {
        private final Semaphore inflight;
        private boolean released;

        PermitRelease(Semaphore inflight) {
            this.inflight = inflight;
        }

        synchronized void releaseOnce() {
            if (!released) {
                released = true;
                inflight.release();
            }
        }
    }
}
