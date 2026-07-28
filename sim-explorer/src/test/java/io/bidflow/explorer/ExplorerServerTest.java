package io.bidflow.explorer;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Functional tests over the explorer's real HTTP surface on an ephemeral loopback port.
 *
 * <p>These tests pin the control-plane wiring and JSON contract. Simulation and budget
 * invariants remain covered by their owning modules.
 */
class ExplorerServerTest {

    private static ExplorerServer server;
    private static HttpClient client;
    private static String base;

    @BeforeAll
    static void boot() throws Exception {
        server = new ExplorerServer(0);
        server.start();
        base = "http://127.0.0.1:" + server.port();
        client = HttpClient.newHttpClient();
    }

    @AfterAll
    static void shutdown() {
        server.stop();
    }

    @BeforeEach
    void resetScenario() throws Exception {
        assertThat(post("/api/reset?seed=1&shards=4&budget=10000000&network=lan&skew=0&traffic=1")
                        .statusCode())
                .isEqualTo(200);
        assertThat(post("/api/pause").statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("serves the dashboard and static assets")
    void servesDashboardAssets() throws Exception {
        final HttpResponse<String> page = get("/");
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.headers().firstValue("Content-Type"))
                .hasValue("text/html; charset=utf-8");
        assertThat(page.body()).contains("<title>bidflow</title>");
        assertThat(page.body()).contains("id=\"topology-svg\"");

        final HttpResponse<String> index = get("/index.html");
        assertThat(index.statusCode()).isEqualTo(200);
        assertThat(index.body()).isEqualTo(page.body());

        final HttpResponse<String> script = get("/app.js");
        assertThat(script.statusCode()).isEqualTo(200);
        assertThat(script.headers().firstValue("Content-Type"))
                .hasValue("text/javascript; charset=utf-8");
        assertThat(script.body()).contains("SPEED_SLICES");

        final HttpResponse<String> styles = get("/styles.css");
        assertThat(styles.statusCode()).isEqualTo(200);
        assertThat(styles.headers().firstValue("Content-Type"))
                .hasValue("text/css; charset=utf-8");
        assertThat(styles.body()).contains("--bg: #ffffff");
    }

    @Test
    @DisplayName("state exposes the complete dashboard schema")
    void stateExposesDashboardSchema() throws Exception {
        final String state = get("/api/state").body();

        assertThat(state)
                .contains(
                        "\"seed\":",
                        "\"nowNanos\":",
                        "\"eventsFired\":",
                        "\"autoTraffic\":",
                        "\"networkPreset\":",
                        "\"commandCount\":",
                        "\"nextEventSeq\":",
                        "\"playing\":",
                        "\"speed\":",
                        "\"pending\":",
                        "\"authority\":{",
                        "\"budget\":",
                        "\"settled\":",
                        "\"outstanding\":",
                        "\"headroom\":",
                        "\"observed\":",
                        "\"actualSpend\":",
                        "\"overspend\":",
                        "\"spendableRemainder\":",
                        "\"shards\":[",
                        "\"blockedLinks\":[",
                        "\"network\":{",
                        "\"served\":",
                        "\"refused\":",
                        "\"lost\":",
                        "\"restarts\":",
                        "\"traceDigest\":\"");
        assertThat(state).contains("\"id\":0", "\"id\":1", "\"id\":2", "\"id\":3");
        assertThat(stringValue(state, "traceDigest")).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("reset accepts configuration and advance moves simulated counters")
    void resetAndAdvanceMoveCounters() throws Exception {
        assertThat(post("/api/reset?seed=42&shards=2&budget=5000000&network=perfect&traffic=1")
                        .statusCode())
                .isEqualTo(200);
        final String before = get("/api/state").body();

        assertThat(post("/api/advance?nanos=50000000").statusCode()).isEqualTo(200);
        final String after = get("/api/state").body();

        assertThat(longValue(after, "seed")).isEqualTo(42L);
        assertThat(longValue(after, "nowNanos")).isGreaterThan(longValue(before, "nowNanos"));
        assertThat(longValue(after, "eventsFired")).isGreaterThan(longValue(before, "eventsFired"));
        assertThat(longValue(after, "actualSpend")).isGreaterThan(longValue(before, "actualSpend"));
        assertThat(longValue(after, "served")).isGreaterThan(longValue(before, "served"));
    }

    @Test
    @DisplayName("traffic toggles and manual search targets a shard")
    void trafficAndManualSearch() throws Exception {
        assertThat(post("/api/traffic?enabled=0").statusCode()).isEqualTo(200);
        final String before = get("/api/state").body();
        assertThat(before).contains("\"autoTraffic\":false");

        assertThat(post("/api/search?shard=2").statusCode()).isEqualTo(200);
        final String after = get("/api/state").body();
        assertThat(requestTotal(after)).isGreaterThan(requestTotal(before));

        assertThat(post("/api/traffic?enabled=1").statusCode()).isEqualTo(200);
        assertThat(get("/api/state").body()).contains("\"autoTraffic\":true");
    }

    @Test
    @DisplayName("crash and restart update liveness and incarnation")
    void crashAndRestartShard() throws Exception {
        final long incarnation = longValue(shardObject(get("/api/state").body(), 1), "incarnation");

        assertThat(post("/api/crash?shard=1").statusCode()).isEqualTo(200);
        assertThat(shardObject(get("/api/state").body(), 1)).contains("\"alive\":false");

        assertThat(post("/api/restart?shard=1").statusCode()).isEqualTo(200);
        final String restarted = shardObject(get("/api/state").body(), 1);
        assertThat(restarted).contains("\"alive\":true");
        assertThat(longValue(restarted, "incarnation")).isGreaterThan(incarnation);
    }

    @Test
    @DisplayName("partition by shard blocks authority links and heal restores them")
    void partitionAndHealLinks() throws Exception {
        assertThat(post("/api/partition?shard=1").statusCode()).isEqualTo(200);
        assertThat(get("/api/state").body())
                .contains(
                        "\"blockedLinks\":[",
                        "{\"from\":0,\"to\":2}",
                        "{\"from\":2,\"to\":0}");

        assertThat(post("/api/heal?a=0&b=2").statusCode()).isEqualTo(200);
        assertThat(get("/api/state").body()).contains("\"blockedLinks\":[]");
    }

    @Test
    @DisplayName("events endpoint pages by sequence cursor")
    void eventsPageByCursor() throws Exception {
        post("/api/traffic?enabled=0");
        post("/api/search?shard=0");

        final HttpResponse<String> firstResponse = get("/api/events?after=0&limit=2");
        assertThat(firstResponse.statusCode()).isEqualTo(200);
        assertThat(firstResponse.body())
                .contains("\"events\":[", "\"nextSeq\":", "\"truncated\":false");
        final List<Long> firstSeqs = longs(firstResponse.body(), "\"seq\":(\\d+)");
        assertThat(firstSeqs).hasSize(2).isSorted();

        final long cursor = firstSeqs.getLast();
        post("/api/crash?shard=0");
        final String secondPage = get("/api/events?after=" + cursor + "&limit=500").body();
        final List<Long> secondSeqs = longs(secondPage, "\"seq\":(\\d+)");
        assertThat(secondSeqs).isNotEmpty().allMatch(seq -> seq > cursor);
    }

    @Test
    @DisplayName("play and pause retain UI transport flags")
    void playAndPauseFlags() throws Exception {
        assertThat(post("/api/play?speed=20").statusCode()).isEqualTo(200);
        assertThat(get("/api/state").body()).contains("\"playing\":true", "\"speed\":20");

        assertThat(post("/api/pause").statusCode()).isEqualTo(200);
        assertThat(get("/api/state").body()).contains("\"playing\":false", "\"speed\":20");
    }

    @Test
    @DisplayName("rejects malformed commands and wrong methods")
    void rejectsBadInputAndWrongMethods() throws Exception {
        assertThat(post("/api/reset?shards=0").statusCode()).isEqualTo(400);
        assertThat(post("/api/reset?network=moon").statusCode()).isEqualTo(400);
        assertThat(post("/api/reset?traffic=2").statusCode()).isEqualTo(400);
        assertThat(post("/api/step?count=-1").statusCode()).isEqualTo(400);
        assertThat(post("/api/advance").statusCode()).isEqualTo(400);
        assertThat(post("/api/search?shard=99").statusCode()).isEqualTo(400);
        assertThat(post("/api/partition?a=0").statusCode()).isEqualTo(400);
        assertThat(post("/api/heal").statusCode()).isEqualTo(400);
        assertThat(get("/api/events?after=-1").statusCode()).isEqualTo(400);
        assertThat(get("/api/reset").statusCode()).isEqualTo(405);
        assertThat(post("/api/state").statusCode()).isEqualTo(405);
        assertThat(get("/api/nope").statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("replay reproduces the trace digest after the same command journal")
    void replayKeepsDeterminism() throws Exception {
        post("/api/reset?seed=77&shards=2&budget=3000000&network=perfect&traffic=1");
        post("/api/advance?nanos=30000000");
        post("/api/traffic?enabled=0");
        post("/api/search?shard=0");
        post("/api/step?count=25");
        post("/api/crash?shard=1");
        post("/api/restart?shard=1");
        post("/api/advance?nanos=10000000");
        final String digest = stringValue(get("/api/state").body(), "traceDigest");

        assertThat(post("/api/replay").statusCode()).isEqualTo(200);

        assertThat(stringValue(get("/api/state").body(), "traceDigest")).isEqualTo(digest);
    }

    private static long requestTotal(String state) {
        return longValue(state, "served") + longValue(state, "refused") + longValue(state, "lost");
    }

    private static String shardObject(String state, int shard) {
        final Matcher matcher = Pattern.compile("\\{\"id\":" + shard + ",[^}]*}").matcher(state);
        assertThat(matcher.find()).as("shard %d in state", shard).isTrue();
        return matcher.group();
    }

    private static long longValue(String text, String key) {
        final Matcher matcher = Pattern.compile("\"" + Pattern.quote(key) + "\":(-?\\d+)").matcher(text);
        assertThat(matcher.find()).as("numeric key %s", key).isTrue();
        return Long.parseLong(matcher.group(1));
    }

    private static String stringValue(String text, String key) {
        final Matcher matcher =
                Pattern.compile("\"" + Pattern.quote(key) + "\":\"([^\"]*)\"").matcher(text);
        assertThat(matcher.find()).as("string key %s", key).isTrue();
        return matcher.group(1);
    }

    private static List<Long> longs(String text, String pattern) {
        final Matcher matcher = Pattern.compile(pattern).matcher(text);
        final List<Long> out = new ArrayList<>();
        while (matcher.find()) {
            out.add(Long.parseLong(matcher.group(1)));
        }
        return out;
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(base + path))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
