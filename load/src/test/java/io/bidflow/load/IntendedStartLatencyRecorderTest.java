package io.bidflow.load;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;
import org.HdrHistogram.Histogram;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IntendedStartLatencyRecorderTest {

    @Test
    @DisplayName("records latency from the intended start, not from a later send time")
    void recordsFromIntendedStart() throws Exception {
        final IntendedStartLatencyRecorder recorder =
                new IntendedStartLatencyRecorder(TimeUnit.SECONDS.toNanos(5));
        final long intended = System.nanoTime();
        Thread.sleep(20);
        // A stalled client that "sends" late still attributes the wait to this sample.
        recorder.recordCompletion(intended);
        final Histogram snap = recorder.snapshot();
        assertThat(snap.getTotalCount()).isEqualTo(1);
        assertThat(snap.getMinValue()).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(15));
    }

    @Test
    @DisplayName("JSON report contains the percentile fields")
    void jsonContainsPercentiles() {
        final IntendedStartLatencyRecorder recorder =
                new IntendedStartLatencyRecorder(TimeUnit.SECONDS.toNanos(5));
        final long now = System.nanoTime();
        recorder.recordCompletion(now - 1_000_000L);
        final String json = IntendedStartLatencyRecorder.toJson(recorder.snapshot());
        assertThat(json).contains("\"p50\":").contains("\"p99\":").contains("\"p999\":");
    }
}
