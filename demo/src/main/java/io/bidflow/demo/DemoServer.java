package io.bidflow.demo;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.bidflow.auction.AuctionEngine;
import io.bidflow.auction.AuctionOutcome;
import io.bidflow.auction.AuctionRequest;
import io.bidflow.budget.BudgetAuthority;
import io.bidflow.budget.Lease;
import io.bidflow.budget.SpendAuthority;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * A one-process, browser-visible demo of the auction and the budget module working together.
 *
 * <p>Two simulated users share the same campaigns and the same bids but carry different
 * predicted quality scores, so the same {@link AuctionEngine} ranks and prices their pages
 * differently — the auction is a pure function of its request, and "the user" enters only
 * through the quality input. Clicks charge real {@link SpendAuthority} wallets holding
 * leases granted by per-campaign {@link BudgetAuthority} instances, so budgets deplete
 * over time and exhausted campaigns drop out of later auctions.
 *
 * <p>The scenario is fully editable at runtime — campaigns, bids, per-user quality,
 * budgets, the slot count, and the reserve — so the page is a sandbox over the real
 * engine rather than a canned dataset.
 *
 * <p><b>Deliberately collapsed:</b> everything runs in one JVM on one clock. No skew, no
 * partitions, no crashes — every lease is released voluntarily, so overspend is
 * structurally zero here. The distributed failure modes are what the {@code sim}-based
 * tests cover; this server exists to make the mechanism visible, not to prove it safe.
 *
 * <p>Zero dependencies beyond the JDK: {@code jdk.httpserver}, a hand-rolled JSON emitter,
 * and query-string inputs. A demo harness, not a serving layer.
 */
public final class DemoServer {

    private static final int MAX_SLOTS = 5;
    private static final int MAX_CANDIDATES = 8;

    private static final long DEFAULT_BUDGET_MICROS = 500_000L;
    private static final long LEASE_MICROS = 150_000L;
    private static final long LEASE_DURATION_NANOS = 15_000_000_000L;
    private static final long LOW_WATER_MICROS = 60_000L;
    private static final long RENEW_AHEAD_NANOS = 3_000_000_000L;

    /** Click-through odds by position, scaling the quality score in the traffic burst. */
    private static final double[] POSITION_BIAS = {1.0, 0.55, 0.30, 0.18, 0.10};

    private static final String JSON = "application/json; charset=utf-8";

    /** One advertiser: shared bid, per-user quality, and its own budget and wallet. */
    private static final class Campaign {
        final long id;
        final String name;
        long bidMicros;
        int qualityA;
        int qualityB;
        BudgetAuthority authority;
        SpendAuthority wallet;
        /** Ground truth, tallied by the demo itself — same habit as the sim harness. */
        long chargedMicros;
        long refusedClicks;

        Campaign(long id, String name, long bidMicros, int qualityA, int qualityB) {
            this.id = id;
            this.name = name;
            this.bidMicros = bidMicros;
            this.qualityA = qualityA;
            this.qualityB = qualityB;
        }
    }

    private record SlotView(
            long campaignId, String name, long bidMicros, int qualityBps, long adRank, long priceMicros) {}

    private final int requestedPort;
    private final byte[] indexHtml;
    private HttpServer server;
    private final AuctionEngine engine = new AuctionEngine(MAX_CANDIDATES);
    private final AuctionRequest request = new AuctionRequest(MAX_CANDIDATES);
    private final AuctionOutcome outcome = new AuctionOutcome(MAX_SLOTS);
    private final List<Campaign> campaigns = new ArrayList<>();
    private final Map<String, List<SlotView>> lastAuction = new HashMap<>();
    /** Cumulative charged micros per campaign, snapshotted after each traffic search. */
    private final List<long[]> timeline = new ArrayList<>();
    private Random clicks = new Random(42);
    private int slots = 3;
    private long reserveMicros = 10_000L;
    private long nextCampaignId = 1;

    /** @param port TCP port to bind, or 0 to let the OS pick one (used by the tests) */
    public DemoServer(int port) throws IOException {
        this.requestedPort = port;
        this.indexHtml = loadIndexHtml();
        resetScenario(DEFAULT_BUDGET_MICROS);
    }

    public static void main(String[] args) throws IOException {
        final int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        new DemoServer(port).start();
        System.out.println("bidflow demo: http://localhost:" + port);
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(requestedPort), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    /** The port actually bound, which differs from the requested one when it was 0. */
    public int port() {
        return server.getAddress().getPort();
    }

    public void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            synchronized (this) {
                route(exchange);
            }
        } finally {
            exchange.close();
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        final String path = exchange.getRequestURI().getPath();
        final Map<String, String> params = query(exchange);
        switch (path) {
            case "/", "/index.html" -> {
                if (requireMethod(exchange, "GET")) {
                    respond(exchange, 200, "text/html; charset=utf-8", indexHtml);
                }
            }
            case "/api/state" -> {
                if (requireMethod(exchange, "GET")) {
                    respond(exchange, 200, JSON, stateJson().getBytes(StandardCharsets.UTF_8));
                }
            }
            case "/api/search" -> {
                if (!requireMethod(exchange, "POST")) {
                    return;
                }
                final String user = userParam(params);
                if (user == null) {
                    respondJson(exchange, 400, "{\"error\":\"user must be A or B\"}");
                    return;
                }
                runSearch(user);
                respondJson(exchange, 200, "{\"ok\":true}");
            }
            case "/api/click" -> {
                if (!requireMethod(exchange, "POST")) {
                    return;
                }
                final String user = userParam(params);
                final Integer slot = intParam(params, "slot");
                if (user == null || slot == null) {
                    respondJson(exchange, 400, "{\"error\":\"need user=A|B and slot\"}");
                    return;
                }
                respondJson(exchange, 200, "{\"charged\":" + chargeClick(user, slot) + "}");
            }
            case "/api/campaign" -> {
                if (!requireMethod(exchange, "POST")) {
                    return;
                }
                final boolean ok = updateCampaign(params);
                respondJson(exchange, ok ? 200 : 400,
                        ok ? "{\"ok\":true}" : "{\"error\":\"bad campaign update\"}");
            }
            case "/api/campaign/add" -> {
                if (!requireMethod(exchange, "POST")) {
                    return;
                }
                final String error = addCampaign(params);
                respondJson(exchange, error == null ? 200 : 400,
                        error == null ? "{\"ok\":true}" : "{\"error\":\"" + error + "\"}");
            }
            case "/api/campaign/remove" -> {
                if (!requireMethod(exchange, "POST")) {
                    return;
                }
                final Long id = longParam(params, "id");
                final boolean removed = id != null && removeCampaign(id);
                respondJson(exchange, removed ? 200 : 400,
                        removed ? "{\"ok\":true}" : "{\"error\":\"unknown campaign\"}");
            }
            case "/api/settings" -> {
                if (!requireMethod(exchange, "POST")) {
                    return;
                }
                final boolean ok = updateSettings(params);
                respondJson(exchange, ok ? 200 : 400,
                        ok ? "{\"ok\":true}" : "{\"error\":\"slots must be 1-" + MAX_SLOTS
                                + " and reserve 0-" + AuctionRequest.MAX_BID_MICROS + "\"}");
            }
            case "/api/traffic" -> {
                if (!requireMethod(exchange, "POST")) {
                    return;
                }
                final Integer requested = intParam(params, "count");
                final int count = Math.clamp(requested == null ? 50 : requested, 1, 200);
                runTraffic(count);
                respondJson(exchange, 200, "{\"ok\":true,\"searches\":" + count + "}");
            }
            case "/api/reset" -> {
                if (!requireMethod(exchange, "POST")) {
                    return;
                }
                final Long budget = longParam(params, "budget");
                resetScenario(budget == null ? DEFAULT_BUDGET_MICROS : Math.clamp(budget, 100_000L, 10_000_000L));
                respondJson(exchange, 200, "{\"ok\":true}");
            }
            default -> respondJson(exchange, 404, "{\"error\":\"not found\"}");
        }
    }

    // ------------------------------------------------------------------ scenario

    private void resetScenario(long budgetMicros) {
        campaigns.clear();
        nextCampaignId = 1;
        slots = 3;
        reserveMicros = 10_000L;
        newCampaign("Peak Performance", 100_000L, 4_000, 9_500, budgetMicros);
        newCampaign("StrideLab", 80_000L, 9_000, 6_000, budgetMicros);
        newCampaign("UrbanKicks", 60_000L, 8_000, 3_000, budgetMicros);
        newCampaign("FlexFit", 45_000L, 5_000, 7_500, budgetMicros);
        lastAuction.clear();
        timeline.clear();
        clicks = new Random(42);
    }

    private void newCampaign(String name, long bidMicros, int qualityA, int qualityB, long budgetMicros) {
        final Campaign c = new Campaign(nextCampaignId++, name, bidMicros, qualityA, qualityB);
        c.authority = new BudgetAuthority(
                budgetMicros, 1, LEASE_DURATION_NANOS, BudgetAuthority.NEVER_RECLAIM);
        c.wallet = new SpendAuthority(0, 1L);
        campaigns.add(c);
    }

    /**
     * One user's search: an auction over the campaigns whose wallets can still cover a
     * worst-case click. This is the pre-auction budget check — local state only, no
     * network — that the lease mechanism exists to make possible.
     */
    private void runSearch(String user) {
        final long now = System.nanoTime();
        request.reset(slots, reserveMicros);
        for (Campaign c : campaigns) {
            ensureLease(c, now);
            if (canServe(c, now)) {
                request.add(c.id, c.bidMicros, "A".equals(user) ? c.qualityA : c.qualityB);
            }
        }
        engine.run(request, outcome);
        final List<SlotView> page = new ArrayList<>(outcome.size());
        for (int k = 0; k < outcome.size(); k++) {
            final Campaign c = byId(outcome.campaignId(k));
            page.add(new SlotView(
                    c.id, c.name, c.bidMicros, outcome.qualityBps(k), outcome.adRank(k), outcome.priceMicros(k)));
        }
        lastAuction.put(user, page);
    }

    /** Charges one click at the price the auction set. Refusal means the ad was free. */
    private boolean chargeClick(String user, int slot) {
        final List<SlotView> page = lastAuction.get(user);
        if (page == null || slot < 0 || slot >= page.size()) {
            return false;
        }
        final SlotView shown = page.get(slot);
        final Campaign c = byId(shown.campaignId());
        final long now = System.nanoTime();
        ensureLease(c, now);
        if (c.wallet.tryReserve(now, shown.priceMicros())) {
            c.chargedMicros += shown.priceMicros();
            return true;
        }
        c.refusedClicks++;
        return false;
    }

    /**
     * A burst of alternating searches with clicks drawn from the quality scores — which is
     * exactly what a quality score predicts. Position bias makes lower slots worth less,
     * which is why winning cheaply at position 1 beats overpaying for it.
     */
    private void runTraffic(int count) {
        for (int i = 0; i < count; i++) {
            final String user = (i % 2 == 0) ? "A" : "B";
            runSearch(user);
            final List<SlotView> page = lastAuction.get(user);
            for (int k = 0; k < page.size(); k++) {
                final double clickOdds = (page.get(k).qualityBps() / 10_000.0) * POSITION_BIAS[k];
                if (clicks.nextDouble() < clickOdds) {
                    chargeClick(user, k);
                }
            }
            final long[] snapshot = new long[campaigns.size()];
            for (int c = 0; c < campaigns.size(); c++) {
                snapshot[c] = campaigns.get(c).chargedMicros;
            }
            timeline.add(snapshot);
        }
        while (timeline.size() > 1_000) {
            timeline.remove(0);
        }
    }

    /**
     * Seal-then-ask renewal, exactly the protocol the budget module defines. Run lazily
     * before every serve and charge rather than on a background timer, so the demo stays
     * single-threaded like the simulation.
     */
    private void ensureLease(Campaign c, long now) {
        final long lowWater = Math.max(LOW_WATER_MICROS, c.bidMicros);
        if (!c.wallet.needsLease(now, lowWater, RENEW_AHEAD_NANOS)) {
            return;
        }
        final long sealedLeaseId = c.wallet.leaseId();
        final long sealedSpent = c.wallet.sealForRenewal();
        final Lease lease = c.authority.requestLease(
                0, 1L, sealedLeaseId, sealedSpent, Math.max(LEASE_MICROS, c.bidMicros), now);
        if (lease != null) {
            c.wallet.installLease(lease, now);
        }
    }

    /** Affordability is judged against the bid — the worst a single click can cost. */
    private boolean canServe(Campaign c, long now) {
        return !c.wallet.isSealed()
                && !c.wallet.isExpired(now)
                && c.wallet.remainingMicros() >= c.bidMicros;
    }

    private boolean updateCampaign(Map<String, String> params) {
        final Long id = longParam(params, "id");
        if (id == null) {
            return false;
        }
        final Campaign c;
        try {
            c = byId(id);
        } catch (IllegalArgumentException e) {
            return false;
        }
        final Long bid = longParam(params, "bid");
        final Integer qualityA = intParam(params, "qualityA");
        final Integer qualityB = intParam(params, "qualityB");
        if (bid != null && (bid < 0 || bid > AuctionRequest.MAX_BID_MICROS)) {
            return false;
        }
        if (qualityA != null && (qualityA < 1 || qualityA > AuctionRequest.QUALITY_ONE_BPS)) {
            return false;
        }
        if (qualityB != null && (qualityB < 1 || qualityB > AuctionRequest.QUALITY_ONE_BPS)) {
            return false;
        }
        if (bid != null) {
            c.bidMicros = bid;
        }
        if (qualityA != null) {
            c.qualityA = qualityA;
        }
        if (qualityB != null) {
            c.qualityB = qualityB;
        }
        return true;
    }

    /** @return null on success, otherwise a short reason (plain ASCII, safe to embed in JSON) */
    private String addCampaign(Map<String, String> params) {
        if (campaigns.size() >= MAX_CANDIDATES) {
            return "market is full: the engine is sized for " + MAX_CANDIDATES + " candidates";
        }
        final String name = cleanName(params.get("name"));
        if (name == null) {
            return "name must be 1-40 printable characters";
        }
        final Long bid = longParamOr(params, "bid", 50_000L);
        final Integer qualityA = intParamOr(params, "qualityA", 5_000);
        final Integer qualityB = intParamOr(params, "qualityB", 5_000);
        final Long budget = longParamOr(params, "budget", DEFAULT_BUDGET_MICROS);
        if (bid == null || bid < 0 || bid > AuctionRequest.MAX_BID_MICROS) {
            return "bid must be 0-" + AuctionRequest.MAX_BID_MICROS + " micros";
        }
        if (qualityA == null || qualityA < 1 || qualityA > AuctionRequest.QUALITY_ONE_BPS
                || qualityB == null || qualityB < 1 || qualityB > AuctionRequest.QUALITY_ONE_BPS) {
            return "quality must be 1-" + AuctionRequest.QUALITY_ONE_BPS + " basis points";
        }
        if (budget == null || budget < 10_000L || budget > 10_000_000L) {
            return "budget must be 10,000-10,000,000 micros";
        }
        newCampaign(name, bid, qualityA, qualityB, budget);
        // The market changed shape: old pages and the old spend timeline no longer describe it.
        lastAuction.clear();
        timeline.clear();
        return null;
    }

    private boolean removeCampaign(long id) {
        for (int i = 0; i < campaigns.size(); i++) {
            if (campaigns.get(i).id == id) {
                campaigns.remove(i);
                lastAuction.clear();
                timeline.clear();
                return true;
            }
        }
        return false;
    }

    private boolean updateSettings(Map<String, String> params) {
        final Integer wantedSlots =
                params.containsKey("slots") ? intParam(params, "slots") : Integer.valueOf(slots);
        final Long wantedReserve =
                params.containsKey("reserve") ? longParam(params, "reserve") : Long.valueOf(reserveMicros);
        if (wantedSlots == null || wantedSlots < 1 || wantedSlots > MAX_SLOTS) {
            return false;
        }
        if (wantedReserve == null || wantedReserve < 0 || wantedReserve > AuctionRequest.MAX_BID_MICROS) {
            return false;
        }
        slots = wantedSlots;
        reserveMicros = wantedReserve;
        // Old pages were priced under the old rules; both users must search again.
        lastAuction.clear();
        return true;
    }

    /** Trims and bounds a user-supplied name, refusing control characters outright. */
    private static String cleanName(String raw) {
        if (raw == null) {
            return null;
        }
        final String name = raw.strip();
        if (name.isEmpty() || name.length() > 40) {
            return null;
        }
        for (int i = 0; i < name.length(); i++) {
            final char ch = name.charAt(i);
            if (ch < 0x20 || ch == 0x7f) {
                return null;
            }
        }
        return name;
    }

    private Campaign byId(long id) {
        for (Campaign c : campaigns) {
            if (c.id == id) {
                return c;
            }
        }
        throw new IllegalArgumentException("unknown campaign " + id);
    }

    // ------------------------------------------------------------------ json

    private String stateJson() {
        final long now = System.nanoTime();
        final StringBuilder sb = new StringBuilder(4_096);
        sb.append("{\"params\":{\"slots\":").append(slots)
                .append(",\"reserveMicros\":").append(reserveMicros)
                .append(",\"maxSlots\":").append(MAX_SLOTS)
                .append(",\"maxCampaigns\":").append(MAX_CANDIDATES)
                .append(",\"leaseMicros\":").append(LEASE_MICROS)
                .append(",\"leaseDurationMillis\":").append(LEASE_DURATION_NANOS / 1_000_000L)
                .append("},\"campaigns\":[");
        for (int i = 0; i < campaigns.size(); i++) {
            final Campaign c = campaigns.get(i);
            if (i > 0) {
                sb.append(',');
            }
            final long expiresIn = c.wallet.leaseId() == Lease.NONE
                    ? -1
                    : Math.max(0, (c.wallet.leaseExpiresAtNanos() - now) / 1_000_000L);
            sb.append("{\"id\":").append(c.id)
                    .append(",\"name\":\"").append(escapeJson(c.name)).append('"')
                    .append(",\"bidMicros\":").append(c.bidMicros)
                    .append(",\"qualityA\":").append(c.qualityA)
                    .append(",\"qualityB\":").append(c.qualityB)
                    .append(",\"budgetMicros\":").append(c.authority.budgetMicros())
                    .append(",\"settledMicros\":").append(c.authority.settledMicros())
                    .append(",\"outstandingMicros\":").append(c.authority.outstandingMicros())
                    .append(",\"headroomMicros\":").append(c.authority.headroomMicros())
                    .append(",\"chargedMicros\":").append(c.chargedMicros)
                    .append(",\"refusedClicks\":").append(c.refusedClicks)
                    .append(",\"walletRemainingMicros\":").append(c.wallet.remainingMicros())
                    .append(",\"walletSealed\":").append(c.wallet.isSealed())
                    .append(",\"walletExpiresInMillis\":").append(expiresIn)
                    .append(",\"serving\":").append(canServe(c, now))
                    .append('}');
        }
        sb.append("],\"auctions\":{");
        appendAuction(sb, "A");
        sb.append(',');
        appendAuction(sb, "B");
        sb.append("},\"timeline\":{\"names\":[");
        for (int i = 0; i < campaigns.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(escapeJson(campaigns.get(i).name)).append('"');
        }
        sb.append("],\"series\":[");
        for (int t = 0; t < timeline.size(); t++) {
            if (t > 0) {
                sb.append(',');
            }
            final long[] snapshot = timeline.get(t);
            sb.append('[');
            for (int c = 0; c < snapshot.length; c++) {
                if (c > 0) {
                    sb.append(',');
                }
                sb.append(snapshot[c]);
            }
            sb.append(']');
        }
        sb.append("]}}");
        return sb.toString();
    }

    /** Emits {@code null} for a user who has not searched yet — distinct from an empty page. */
    private void appendAuction(StringBuilder sb, String user) {
        sb.append('"').append(user).append("\":");
        final List<SlotView> page = lastAuction.get(user);
        if (page == null) {
            sb.append("null");
            return;
        }
        sb.append('[');
        for (int k = 0; k < page.size(); k++) {
            final SlotView s = page.get(k);
            if (k > 0) {
                sb.append(',');
            }
            sb.append("{\"campaignId\":").append(s.campaignId())
                    .append(",\"name\":\"").append(escapeJson(s.name())).append('"')
                    .append(",\"bidMicros\":").append(s.bidMicros())
                    .append(",\"qualityBps\":").append(s.qualityBps())
                    .append(",\"adRank\":").append(s.adRank())
                    .append(",\"priceMicros\":").append(s.priceMicros())
                    .append('}');
        }
        sb.append(']');
    }

    // ------------------------------------------------------------------ http plumbing

    private static boolean requireMethod(HttpExchange exchange, String method) throws IOException {
        if (method.equals(exchange.getRequestMethod())) {
            return true;
        }
        respondJson(exchange, 405, "{\"error\":\"use " + method + "\"}");
        return false;
    }

    private static void respondJson(HttpExchange exchange, int status, String body) throws IOException {
        respond(exchange, status, JSON, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static Map<String, String> query(HttpExchange exchange) {
        final Map<String, String> out = new HashMap<>();
        final String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null) {
            return out;
        }
        for (String pair : raw.split("&")) {
            final int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(
                        URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static String userParam(Map<String, String> params) {
        final String user = params.get("user");
        return "A".equals(user) || "B".equals(user) ? user : null;
    }

    private static Integer intParam(Map<String, String> params, String name) {
        final String value = params.get(name);
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long longParam(Map<String, String> params, String name) {
        final String value = params.get(name);
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Falls back when the parameter is absent; a present-but-malformed value stays null. */
    private static Long longParamOr(Map<String, String> params, String name, long fallback) {
        return params.containsKey(name) ? longParam(params, name) : Long.valueOf(fallback);
    }

    private static Integer intParamOr(Map<String, String> params, String name, int fallback) {
        return params.containsKey(name) ? intParam(params, name) : Integer.valueOf(fallback);
    }

    private static String escapeJson(String value) {
        final StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            final char ch = value.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static byte[] loadIndexHtml() throws IOException {
        try (InputStream in = DemoServer.class.getResourceAsStream("index.html")) {
            if (in == null) {
                throw new IOException("index.html resource missing");
            }
            return in.readAllBytes();
        }
    }
}
