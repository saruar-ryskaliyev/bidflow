package io.bidflow.explorer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.bidflow.budget.sim.ClusterSnapshot;
import io.bidflow.budget.sim.ClusterSnapshot.BlockedLink;
import io.bidflow.budget.sim.ShardSnapshot;
import io.bidflow.sim.Simulation.PendingEvent;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Loopback-only HTTP control plane and static resource server for the shard explorer.
 *
 * <p>The server intentionally uses only JDK facilities. Requests carry query-string
 * parameters and responses use a hand-rolled JSON emitter, keeping simulation time and
 * control entirely explicit.
 */
public final class ExplorerServer {

    private static final String LOOPBACK = "127.0.0.1";
    private static final String JSON = "application/json; charset=utf-8";

    private static final long DEFAULT_SEED = 1L;
    private static final int DEFAULT_SHARDS = 4;
    private static final long DEFAULT_BUDGET_MICROS = 10_000_000L;
    private static final String DEFAULT_NETWORK = "lan";
    private static final int DEFAULT_EVENT_LIMIT = 500;
    private static final int MAX_EVENT_LIMIT = 2_000;

    private final int requestedPort;
    private final byte[] indexHtml;
    private final byte[] appJs;
    private final byte[] stylesCss;
    private final ExplorerSession session;

    private HttpServer server;
    private boolean playing;
    private int speed = 1;

    /** @param port TCP port to bind, or 0 to let the OS choose one for tests */
    public ExplorerServer(int port) throws IOException {
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("port must be in [0, 65535], was " + port);
        }
        requestedPort = port;
        indexHtml = loadResource("index.html");
        appJs = loadResource("app.js");
        stylesCss = loadResource("styles.css");
        session = new ExplorerSession(
                DEFAULT_SEED,
                DEFAULT_SHARDS,
                DEFAULT_BUDGET_MICROS,
                DEFAULT_NETWORK,
                0L,
                null,
                true,
                null);
    }

    public static void main(String[] args) throws IOException {
        final int port = args.length > 0 ? Integer.parseInt(args[0]) : 8081;
        final ExplorerServer explorer = new ExplorerServer(port);
        explorer.start();
        System.out.println("bidflow sim-explorer: http://" + LOOPBACK + ":" + explorer.port());
    }

    /** Starts the loopback listener. */
    public synchronized void start() throws IOException {
        if (server != null) {
            throw new IllegalStateException("server already started");
        }
        server = HttpServer.create(new InetSocketAddress(LOOPBACK, requestedPort), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    /** The actual bound port, including the OS-selected value when constructed with 0. */
    public synchronized int port() {
        if (server == null) {
            throw new IllegalStateException("server is not started");
        }
        return server.getAddress().getPort();
    }

    /** Stops the listener immediately. Safe to call more than once. */
    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            synchronized (this) {
                try {
                    route(exchange);
                } catch (IllegalArgumentException e) {
                    respondError(exchange, 400, e.getMessage());
                }
            }
        } finally {
            exchange.close();
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        final String path = exchange.getRequestURI().getPath();
        final Map<String, String> params = query(exchange);
        switch (path) {
            case "/", "/index.html" -> serve(exchange, "GET", "text/html; charset=utf-8", indexHtml);
            case "/app.js" -> serve(exchange, "GET", "text/javascript; charset=utf-8", appJs);
            case "/styles.css" -> serve(exchange, "GET", "text/css; charset=utf-8", stylesCss);
            case "/api/state" -> {
                if (requireMethod(exchange, "GET")) {
                    respondJson(exchange, 200, stateJson());
                }
            }
            case "/api/events" -> {
                if (!requireMethod(exchange, "GET")) {
                    return;
                }
                final long after = optionalLong(params, "after", 0L);
                if (after < 0) {
                    throw new IllegalArgumentException("after must not be negative");
                }
                final int requestedLimit = optionalInt(params, "limit", DEFAULT_EVENT_LIMIT);
                final int limit = Math.clamp(requestedLimit, 1, MAX_EVENT_LIMIT);
                respondJson(exchange, 200, eventsJson(session.eventsAfter(after, limit)));
            }
            case "/api/reset" -> {
                if (!requireMethod(exchange, "POST")) {
                    return;
                }
                final long seed = optionalLong(params, "seed", DEFAULT_SEED);
                final int shards = optionalInt(params, "shards", DEFAULT_SHARDS);
                final long budget = optionalLong(params, "budget", DEFAULT_BUDGET_MICROS);
                final String network = params.getOrDefault("network", DEFAULT_NETWORK)
                        .toLowerCase(Locale.ROOT);
                final long skewMillis = optionalLong(params, "skew", 0L);
                final long skewNanos;
                try {
                    skewNanos = Math.multiplyExact(skewMillis, 1_000_000L);
                } catch (ArithmeticException e) {
                    throw new IllegalArgumentException("skew is outside the supported range", e);
                }
                final boolean traffic = optionalFlag(params, "traffic", true);
                final Long reclaimMargin = params.containsKey("reclaimMargin")
                        ? requiredLong(params, "reclaimMargin")
                        : null;
                if (reclaimMargin != null && reclaimMargin < 0) {
                    throw new IllegalArgumentException("reclaimMargin must not be negative");
                }
                session.apply(new ExplorerCommand.Reset(
                        seed, shards, budget, network, skewNanos, null, traffic, reclaimMargin));
                playing = false;
                respondOk(exchange);
            }
            case "/api/step" -> {
                if (!requireMethod(exchange, "POST")) {
                    return;
                }
                final long count = optionalLong(params, "count", 1L);
                if (count < 0) {
                    throw new IllegalArgumentException("count must not be negative");
                }
                if (count == 1L) {
                    session.apply(new ExplorerCommand.Step());
                } else {
                    session.apply(new ExplorerCommand.StepN(count));
                }
                respondOk(exchange);
            }
            case "/api/advance" -> {
                if (!requireMethod(exchange, "POST")) {
                    return;
                }
                final long nanos = requiredLong(params, "nanos");
                if (nanos < 0) {
                    throw new IllegalArgumentException("nanos must not be negative");
                }
                session.apply(new ExplorerCommand.Advance(nanos));
                respondOk(exchange);
            }
            case "/api/play" -> {
                if (!requireMethod(exchange, "POST")) {
                    return;
                }
                if (params.containsKey("speed")) {
                    speed = speedParam(params);
                }
                playing = true;
                respondOk(exchange);
            }
            case "/api/pause" -> {
                if (!requireMethod(exchange, "POST")) {
                    return;
                }
                if (params.containsKey("speed")) {
                    speed = speedParam(params);
                }
                playing = false;
                respondOk(exchange);
            }
            case "/api/traffic" -> {
                if (!requireMethod(exchange, "POST")) {
                    return;
                }
                session.apply(new ExplorerCommand.SetTraffic(requiredFlag(params, "enabled")));
                respondOk(exchange);
            }
            case "/api/search" -> {
                if (!requireMethod(exchange, "POST")) {
                    return;
                }
                session.apply(new ExplorerCommand.Search(requiredInt(params, "shard")));
                respondOk(exchange);
            }
            case "/api/crash" -> {
                if (!requireMethod(exchange, "POST")) {
                    return;
                }
                session.apply(new ExplorerCommand.CrashShard(requiredInt(params, "shard")));
                respondOk(exchange);
            }
            case "/api/restart" -> {
                if (!requireMethod(exchange, "POST")) {
                    return;
                }
                session.apply(new ExplorerCommand.RestartShard(requiredInt(params, "shard")));
                respondOk(exchange);
            }
            case "/api/partition" -> {
                if (!requireMethod(exchange, "POST")) {
                    return;
                }
                final int[] nodes = partitionNodes(params);
                session.apply(new ExplorerCommand.Partition(nodes[0], nodes[1]));
                respondOk(exchange);
            }
            case "/api/heal" -> {
                if (!requireMethod(exchange, "POST")) {
                    return;
                }
                if (params.containsKey("all")) {
                    if (!requiredFlag(params, "all")) {
                        throw new IllegalArgumentException("all must be 1");
                    }
                    session.apply(new ExplorerCommand.HealAll());
                } else {
                    session.apply(new ExplorerCommand.Heal(
                            requiredInt(params, "a"), requiredInt(params, "b")));
                }
                respondOk(exchange);
            }
            case "/api/replay" -> {
                if (!requireMethod(exchange, "POST")) {
                    return;
                }
                session.apply(new ExplorerCommand.Replay());
                playing = false;
                respondOk(exchange);
            }
            default -> respondError(exchange, 404, "not found");
        }
    }

    private int[] partitionNodes(Map<String, String> params) {
        if (params.containsKey("shard")) {
            final int shard = requiredInt(params, "shard");
            if (shard < 0 || shard >= session.shardCount()) {
                throw new IllegalArgumentException(
                        "shard must be in [0, " + session.shardCount() + "), was " + shard);
            }
            return new int[] {0, ExplorerSession.shardToNode(shard)};
        }
        return new int[] {requiredInt(params, "a"), requiredInt(params, "b")};
    }

    // ------------------------------------------------------------------ JSON

    private String stateJson() {
        final ExplorerState state = session.snapshot();
        final ClusterSnapshot cluster = state.snapshot();
        final StringBuilder out = new StringBuilder(8_192);
        out.append("{\"seed\":").append(state.seed())
                .append(",\"nowNanos\":").append(state.nowNanos())
                .append(",\"eventsFired\":").append(state.eventsFired())
                .append(",\"autoTraffic\":").append(state.autoTraffic())
                .append(",\"networkPreset\":\"").append(escapeJson(state.networkPreset())).append('"')
                .append(",\"commandCount\":").append(state.commandCount())
                .append(",\"nextEventSeq\":").append(state.nextEventSeq())
                .append(",\"playing\":").append(playing)
                .append(",\"speed\":").append(speed)
                .append(",\"pending\":");
        appendPending(out, state.pending().orElse(null));
        out.append(",\"authority\":{\"budget\":").append(cluster.budgetMicros())
                .append(",\"settled\":").append(cluster.settledMicros())
                .append(",\"outstanding\":").append(cluster.outstandingMicros())
                .append(",\"headroom\":").append(cluster.headroomMicros())
                .append(",\"observed\":").append(cluster.observedSpendMicros())
                .append(",\"actualSpend\":").append(cluster.actualSpendMicros())
                .append(",\"overspend\":").append(cluster.overspendMicros())
                .append(",\"spendableRemainder\":").append(cluster.spendableRemainderMicros())
                .append("},\"shards\":[");
        for (int i = 0; i < cluster.shards().size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            appendShard(out, cluster.shards().get(i));
        }
        out.append("],\"blockedLinks\":[");
        for (int i = 0; i < cluster.blockedLinks().size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            final BlockedLink link = cluster.blockedLinks().get(i);
            out.append("{\"from\":").append(link.from())
                    .append(",\"to\":").append(link.to()).append('}');
        }
        out.append("],\"network\":{\"sent\":").append(cluster.networkSent())
                .append(",\"delivered\":").append(cluster.networkDelivered())
                .append(",\"dropped\":").append(cluster.networkDropped())
                .append(",\"duplicated\":").append(cluster.networkDuplicated())
                .append(",\"partitioned\":").append(cluster.networkPartitioned())
                .append("},\"served\":").append(cluster.servedRequests())
                .append(",\"refused\":").append(cluster.refusedRequests())
                .append(",\"lost\":").append(cluster.lostAuctions())
                .append(",\"restarts\":").append(cluster.restarts())
                .append(",\"traceDigest\":\"").append(escapeJson(session.traceDigest())).append("\"}");
        return out.toString();
    }

    private static void appendPending(StringBuilder out, PendingEvent pending) {
        if (pending == null) {
            out.append("null");
            return;
        }
        out.append("{\"timeNanos\":").append(pending.timeNanos())
                .append(",\"sequence\":").append(pending.sequence())
                .append(",\"owner\":").append(pending.owner()).append('}');
    }

    private static void appendShard(StringBuilder out, ShardSnapshot shard) {
        out.append("{\"id\":").append(shard.id())
                .append(",\"alive\":").append(shard.alive())
                .append(",\"incarnation\":").append(shard.incarnation())
                .append(",\"remainingMicros\":").append(shard.remainingMicros())
                .append(",\"leaseId\":").append(shard.leaseId())
                .append(",\"leaseSpentMicros\":").append(shard.leaseSpentMicros())
                .append(",\"lifetimeSpentMicros\":").append(shard.lifetimeSpentMicros())
                .append(",\"expiresAtNanos\":").append(shard.expiresAtNanos())
                .append(",\"clockOffsetNanos\":").append(shard.clockOffsetNanos())
                .append(",\"pendingReleaseId\":").append(shard.pendingReleaseId())
                .append('}');
    }

    private static String eventsJson(ExplorerEventPage page) {
        final StringBuilder out = new StringBuilder(8_192);
        out.append("{\"events\":[");
        for (int i = 0; i < page.events().size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            final ExplorerEvent event = page.events().get(i);
            out.append("{\"seq\":").append(event.seq())
                    .append(",\"timeNanos\":").append(event.timeNanos())
                    .append(",\"kind\":\"").append(escapeJson(event.kind())).append('"')
                    .append(",\"from\":").append(event.from())
                    .append(",\"to\":").append(event.to())
                    .append(",\"label\":\"").append(escapeJson(event.label())).append('"')
                    .append(",\"duplicate\":").append(event.duplicate())
                    .append(",\"detail\":\"").append(escapeJson(event.detail())).append("\"}");
        }
        out.append("],\"nextSeq\":").append(page.nextSeq())
                .append(",\"truncated\":").append(page.truncated()).append('}');
        return out.toString();
    }

    // ------------------------------------------------------------------ HTTP plumbing

    private static void serve(HttpExchange exchange, String method, String contentType, byte[] body)
            throws IOException {
        if (requireMethod(exchange, method)) {
            respond(exchange, 200, contentType, body);
        }
    }

    private static boolean requireMethod(HttpExchange exchange, String method) throws IOException {
        if (method.equals(exchange.getRequestMethod())) {
            return true;
        }
        exchange.getResponseHeaders().set("Allow", method);
        respondError(exchange, 405, "use " + method);
        return false;
    }

    private static void respondOk(HttpExchange exchange) throws IOException {
        respondJson(exchange, 200, "{\"ok\":true}");
    }

    private static void respondError(HttpExchange exchange, int status, String message) throws IOException {
        final String safeMessage = message == null || message.isBlank() ? "bad request" : message;
        respondJson(exchange, status, "{\"error\":\"" + escapeJson(safeMessage) + "\"}");
    }

    private static void respondJson(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
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
        final Map<String, String> params = new HashMap<>();
        final String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return params;
        }
        for (String pair : raw.split("&")) {
            final int equals = pair.indexOf('=');
            if (equals <= 0) {
                throw new IllegalArgumentException("query parameters must use name=value");
            }
            params.put(
                    URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
        }
        return params;
    }

    private static int speedParam(Map<String, String> params) {
        final int requested = requiredInt(params, "speed");
        return switch (requested) {
            case 1, 5, 20, 100 -> requested;
            default -> throw new IllegalArgumentException("speed must be 1, 5, 20, or 100");
        };
    }

    private static boolean optionalFlag(Map<String, String> params, String name, boolean fallback) {
        return params.containsKey(name) ? requiredFlag(params, name) : fallback;
    }

    private static boolean requiredFlag(Map<String, String> params, String name) {
        final String value = params.get(name);
        if ("1".equals(value)) {
            return true;
        }
        if ("0".equals(value)) {
            return false;
        }
        throw new IllegalArgumentException(name + " must be 0 or 1");
    }

    private static int optionalInt(Map<String, String> params, String name, int fallback) {
        return params.containsKey(name) ? requiredInt(params, name) : fallback;
    }

    private static int requiredInt(Map<String, String> params, String name) {
        final String value = params.get(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("missing " + name);
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer", e);
        }
    }

    private static long optionalLong(Map<String, String> params, String name, long fallback) {
        return params.containsKey(name) ? requiredLong(params, name) : fallback;
    }

    private static long requiredLong(Map<String, String> params, String name) {
        final String value = params.get(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("missing " + name);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer", e);
        }
    }

    private static String escapeJson(String value) {
        final StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            final char ch = value.charAt(i);
            switch (ch) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) ch));
                    } else {
                        escaped.append(ch);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static byte[] loadResource(String name) throws IOException {
        try (InputStream in = ExplorerServer.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException(name + " resource missing");
            }
            return in.readAllBytes();
        }
    }
}
