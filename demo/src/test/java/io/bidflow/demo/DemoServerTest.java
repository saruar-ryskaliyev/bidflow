package io.bidflow.demo;

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
 * Functional tests over the demo's HTTP surface: boot the real server on an ephemeral
 * port and drive it the way the page does.
 *
 * <p>The point is not to re-prove the auction or the budget module — their own suites do
 * that — but to pin the wiring between them: eligibility, the prices that reach the page,
 * click charging against real leases, and input validation. The expected figures are the
 * hand-calculated GSP prices for the default scenario, so a change to either the scenario
 * or the pricing shows up here by name.
 */
class DemoServerTest {

    private static DemoServer server;
    private static HttpClient client;
    private static String base;

    @BeforeAll
    static void boot() throws Exception {
        server = new DemoServer(0);
        server.start();
        base = "http://localhost:" + server.port();
        client = HttpClient.newHttpClient();
    }

    @AfterAll
    static void shutdown() {
        server.stop();
    }

    @BeforeEach
    void resetScenario() throws Exception {
        assertThat(post("/api/reset?budget=500000").statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("serves the page")
    void servesThePage() throws Exception {
        final HttpResponse<String> response = get("/");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("text/html; charset=utf-8");
        assertThat(response.body()).contains("<title>bidflow");
    }

    @Test
    @DisplayName("ranks user A's default page by ad rank and prices it by GSP")
    void defaultAuctionMatchesHandCalculation() throws Exception {
        assertThat(post("/api/search?user=A").statusCode()).isEqualTo(200);
        final String auctions = auctionsSection(get("/api/state").body());

        // StrideLab (80k x 0.90 = 7.2e8) beats UrbanKicks (60k x 0.80 = 4.8e8) beats
        // Peak Performance (100k x 0.40 = 4.0e8): quality outranks the deepest pocket.
        assertThat(auctions).contains("\"A\":[{\"campaignId\":2");
        assertThat(auctions.indexOf("\"name\":\"StrideLab\""))
                .isLessThan(auctions.indexOf("\"name\":\"UrbanKicks\""))
                .isLessThan(auctions.indexOf("\"name\":\"Peak Performance\""));

        // ceil(4.8e8 / 9000), ceil(4.0e8 / 8000), ceil(reserveRank 1e8 / 4000).
        assertThat(auctions)
                .contains("\"priceMicros\":53334")
                .contains("\"priceMicros\":50000")
                .contains("\"priceMicros\":56250");
    }

    @Test
    @DisplayName("a click charges the campaign's wallet at the auction price")
    void clickChargesTheWallet() throws Exception {
        post("/api/search?user=A");
        assertThat(post("/api/click?user=A&slot=0").body()).isEqualTo("{\"charged\":true}");

        final Matcher charged = Pattern.compile("\"name\":\"StrideLab\"[^}]*\"chargedMicros\":(\\d+)")
                .matcher(get("/api/state").body());
        assertThat(charged.find()).isTrue();
        assertThat(Long.parseLong(charged.group(1))).isEqualTo(53_334L);
    }

    @Test
    @DisplayName("heavy traffic never charges any campaign beyond its budget")
    void trafficNeverOvercharges() throws Exception {
        post("/api/reset?budget=100000");
        post("/api/traffic?count=200");
        post("/api/traffic?count=200");

        final String state = get("/api/state").body();
        final List<Long> budgets = longs(state, "\"budgetMicros\":(\\d+)");
        final List<Long> charged = longs(state, "\"chargedMicros\":(\\d+)");
        assertThat(charged).hasSameSizeAs(budgets).isNotEmpty();

        long total = 0;
        for (int i = 0; i < budgets.size(); i++) {
            assertThat(charged.get(i)).as("campaign %d", i).isLessThanOrEqualTo(budgets.get(i));
            total += charged.get(i);
        }
        // Guard against a vacuous pass: the burst must actually have spent money, and
        // 400 searches against a small budget must have exhausted somebody.
        assertThat(total).isPositive();
        assertThat(state).contains("\"serving\":false");
    }

    @Test
    @DisplayName("a user-added campaign competes in the next auction")
    void addedCampaignCompetes() throws Exception {
        assertThat(post("/api/campaign/add?name=Newcomer&bid=90000&qualityA=9900&qualityB=100&budget=200000")
                .statusCode()).isEqualTo(200);
        post("/api/search?user=A");

        // 90k x 0.99 = 8.91e8 outranks every default campaign for user A.
        final String auctions = auctionsSection(get("/api/state").body());
        assertThat(auctions).contains("\"A\":[{\"campaignId\":5,\"name\":\"Newcomer\"");
    }

    @Test
    @DisplayName("rejects bad input and wrong methods")
    void rejectsBadInput() throws Exception {
        assertThat(post("/api/campaign/add?name=Test&qualityA=20000").statusCode()).isEqualTo(400);
        assertThat(post("/api/campaign/add?name=").statusCode()).isEqualTo(400);
        assertThat(post("/api/settings?slots=9").statusCode()).isEqualTo(400);
        assertThat(post("/api/campaign/remove?id=999").statusCode()).isEqualTo(400);
        assertThat(get("/api/search").statusCode()).isEqualTo(405);
        assertThat(get("/api/nope").statusCode()).isEqualTo(404);
    }

    private static String auctionsSection(String state) {
        return state.substring(state.indexOf("\"auctions\""));
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
                HttpRequest.newBuilder(URI.create(base + path)).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
