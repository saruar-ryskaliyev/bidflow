package io.bidflow.load;

import static org.assertj.core.api.Assertions.assertThat;

import io.bidflow.serving.AuctionServer;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class OpenLoopLoadGeneratorSmokeTest {

    @Test
    void shortOpenLoopRunAgainstLocalServer() throws Exception {
        try (AuctionServer server = new AuctionServer(0, 2, 32, 64, 8)) {
            server.start();
            final ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", server.port())
                    .usePlaintext()
                    .build();
            try {
                final OpenLoopLoadGenerator.Config config = new OpenLoopLoadGenerator.Config();
                config.host = "localhost";
                config.port = server.port();
                config.rps = 200;
                config.warmupSeconds = 0;
                config.durationSeconds = 1;
                config.candidates = 8;
                config.deadlineMillis = 500;
                config.out = null;
                final OpenLoopLoadGenerator.Result result = OpenLoopLoadGenerator.run(channel, config);
                assertThat(result.histogram().getTotalCount()).isPositive();
                assertThat(server.served()).isPositive();
            } finally {
                channel.shutdownNow();
                channel.awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }
}
