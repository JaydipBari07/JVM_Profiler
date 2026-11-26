package io.profiler.viewer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Usage: java -jar profiler-viewer.jar --dir ./profiler-data [--port 8686] [--bind 127.0.0.1] [--token SECRET]
 */
public final class Viewer {
    private final Store store;
    private final String token;
    private static final int AGG_LIMIT = 3000;

    private Viewer(Store store, String token) {
        this.store = store;
        this.token = token;
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> a = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) a.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        Path dir = Path.of(a.getOrDefault("dir", "./profiler-data")).toAbsolutePath();
        int port = Integer.parseInt(a.getOrDefault("port", "8686"));
        String bind = a.getOrDefault("bind", "127.0.0.1");
        String token = a.getOrDefault("token", System.getenv("PROFILER_TOKEN"));
        if (token != null && token.isBlank()) token = null;
        if (!InetAddress.getByName(bind).isLoopbackAddress() && token == null) {
            System.err.println("Refusing to listen on non-loopback address " + bind + " without --token (or PROFILER_TOKEN).");
            System.exit(2);
        }
        Files.createDirectories(dir);
        Store store = new Store(dir);
        store.refresh();
        ScheduledExecutorService bg = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "store-refresh");
            t.setDaemon(true);
            return t;
        });
        bg.scheduleWithFixedDelay(store::refresh, 3, Integer.parseInt(a.getOrDefault("refresh", "3")), TimeUnit.SECONDS);
        Viewer v = new Viewer(store, token);
        HttpServer server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        server.createContext("/", v::handle);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        int[] c = store.counts();
        System.out.printf("Profiler viewer on http://%s:%d  (dir=%s, %d traces indexed)%s%n", bind, port, dir, c[0],
            token != null ? "  [token required: append ?token=... once]" : "");
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());
            if (!authorized(ex, q)) {
                send(ex, 401, "text/plain", "Unauthorized: supply token via ?token=..., X-Profiler-Token or Authorization: Bearer".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String path = ex.getRequestURI().getPath();
            if (path.startsWith("/api/")) handleApi(ex, path.substring(5), q);
            else staticFile(ex, path);
        } catch (Throwable t) {
            send(ex, 500, "application/json", Json.write(Map.of("error", String.valueOf(t))).getBytes(StandardCharsets.UTF_8));
        } finally {
            ex.close();
        }
    }

    private boolean authorized(HttpExchange ex, Map<String, String> q) {
        if (token == null) return true;
        String got = q.get("token");
        if (got == null) got = ex.getRequestHeaders().getFirst("X-Profiler-Token");
        if (got == null) {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            if (auth != null && auth.startsWith("Bearer ")) got = auth.substring(7);
        }
        boolean fromQuery = got != null && q.get("token") != null;
        if (got == null) {
            String cookie = ex.getRequestHeaders().getFirst("Cookie");
            if (cookie != null) {
                for (String part : cookie.split(";")) {
                    String p = part.trim();
                    if (p.startsWith("ptoken=")) got = URLDecoder.decode(p.substring(7), StandardCharsets.UTF_8);
                }
            }
        }
        if (got == null || !MessageDigest.isEqual(got.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) return false;
        if (fromQuery) ex.getResponseHeaders().add("Set-Cookie", "ptoken=" + java.net.URLEncoder.encode(token, StandardCharsets.UTF_8) + "; Path=/; HttpOnly; SameSite=Strict");
        return true;
    }

    private void handleApi(HttpExchange ex, String ep, Map<String, String> q) throws IOException {
        long now = System.currentTimeMillis();
        long to = queryLong(q, "to", now), from = queryLong(q, "from", to - 3600_000L);
        Object res;
        switch (ep) {
            case "info" -> {
                int[] c = store.counts();
                res = Map.of("traces", c[0], "sampleRecords", c[1], "metricPoints", c[2], "now", now);
            }
            case "traces" -> res = traces(q, from, to);
            case "trace" -> {
                Map<String, Object> t = store.loadTrace(q.getOrDefault("id", ""));
                if (t == null) {
                    send(ex, 404, "application/json", "{\"error\":\"not found\"}".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                if ("1".equals(q.get("dl")))
                    ex.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"trace-" + q.get("id") + ".json\"");
                res = t;
            }
            case "speedscope" -> {
                Map<String, Object> t = store.loadTrace(q.getOrDefault("id", ""));
                if (t == null) {
                    send(ex, 404, "application/json", "{\"error\":\"not found\"}".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                ex.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"trace-" + q.get("id") + ".speedscope.json\"");
                res = Analytics.speedscope(t);
            }
            case "hot" -> {
                List<Store.Summary> sel = select(q, from, to);
                res = Map.of("items", Analytics.hot(store, limit(sel), q.getOrDefault("sort", "total"), (int) queryLong(q, "limit", 100)), "truncated", sel.size() > AGG_LIMIT);
            }
            case "flame" -> res = Analytics.flame(store, limit(select(q, from, to)));
            case "threads" -> res = threads(from, to, q);
            case "samples" -> res = Analytics.samples(store, from, to, q.getOrDefault("thread", ""));
            case "metrics" -> res = Map.of("points", store.metrics(from, to), "events", store.events(from, to));
            default -> {
                send(ex, 404, "application/json", "{\"error\":\"unknown endpoint\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }
        }
        send(ex, 200, "application/json", Json.write(res).getBytes(StandardCharsets.UTF_8));
    }

    private static List<Store.Summary> limit(List<Store.Summary> l) {
        return l.size() > AGG_LIMIT ? l.subList(0, AGG_LIMIT) : l;
    }

    /** Summaries in range matching filters, newest first. */
    private List<Store.Summary> select(Map<String, String> q, long from, long to) {
        String kind = q.getOrDefault("kind", ""), thread = q.getOrDefault("thread", ""), text = q.getOrDefault("q", "").toLowerCase(Locale.ROOT);
        long minNanos = (long) (Double.parseDouble(q.getOrDefault("minMs", "0")) * 1_000_000);
        boolean errOnly = "1".equals(q.get("err")), alertOnly = "1".equals(q.get("alerts"));
        List<Store.Summary> out = new ArrayList<>();
        for (Store.Summary s : store.traces()) {
            if (s.start() > to || s.start() + s.dur() / 1_000_000 < from) continue;
            if (!kind.isEmpty() && !kind.equals(s.kind())) continue;
            if (!thread.isEmpty() && !thread.equals(s.group())) continue;
            if (s.dur() < minNanos) continue;
            if (errOnly && !s.err()) continue;
            if (alertOnly && !s.alert()) continue;
            if (!text.isEmpty() && !(s.title().toLowerCase(Locale.ROOT).contains(text) || (s.rid() != null && s.rid().toLowerCase(Locale.ROOT).contains(text))
                || s.thread().toLowerCase(Locale.ROOT).contains(text))) continue;
            out.add(s);
        }
        out.sort(Comparator.comparingLong(Store.Summary::start).reversed());
        return out;
    }

    private Map<String, Object> traces(Map<String, String> q, long from, long to) {
        List<Store.Summary> sel = select(q, from, to);
        String sort = q.getOrDefault("sort", "start");
        Comparator<Store.Summary> cmp = switch (sort) {
            case "dur" -> Comparator.comparingLong(Store.Summary::dur).reversed();
            case "alloc" -> Comparator.comparingLong(Store.Summary::alloc).reversed();
            case "cpu" -> Comparator.comparingLong(Store.Summary::cpu).reversed();
            default -> null;
        };
        if (cmp != null) sel.sort(cmp);
        int lim = (int) queryLong(q, "limit", 200);
        List<Object> items = new ArrayList<>();
        for (Store.Summary s : sel.subList(0, Math.min(lim, sel.size()))) items.add(s.toMap());
        return Map.of("total", sel.size(), "items", items);
    }

    /** Empty per-thread-group statistics record (same fields whether filled from traces or samples). */
    private static Map<String, Object> newGroup(String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("group", name);
        m.put("traces", 0L);
        m.put("requests", 0L);
        m.put("totalNanos", 0L);
        m.put("maxNanos", 0L);
        m.put("alloc", 0L);
        m.put("errors", 0L);
        m.put("samples", 0L);
        m.put("threads", 0L);
        m.put("states", new LinkedHashMap<String, Long>());
        return m;
    }

    @SuppressWarnings("unchecked")
    private Object threads(long from, long to, Map<String, String> q) {
        Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
        Map<String, String> q2 = new HashMap<>(q);
        q2.remove("thread");
        for (Store.Summary s : select(q2, from, to)) {
            Map<String, Object> g = groups.computeIfAbsent(s.group(), Viewer::newGroup);
            g.put("traces", (Long) g.get("traces") + 1);
            if ("request".equals(s.kind())) g.put("requests", (Long) g.get("requests") + 1);
            g.put("totalNanos", (Long) g.get("totalNanos") + s.dur());
            g.put("maxNanos", Math.max((Long) g.get("maxNanos"), s.dur()));
            g.put("alloc", (Long) g.get("alloc") + s.alloc());
            if (s.err()) g.put("errors", (Long) g.get("errors") + 1);
        }
        for (Store.SampleRef r : store.samples(from, to)) {
            Map<String, Object> rec = store.loadSample(r);
            if (rec == null) continue;
            for (Map<String, Object> t : (List<Map<String, Object>>) rec.get("threads")) {
                String name = (String) t.get("g");
                Map<String, Object> g = groups.computeIfAbsent(name, Viewer::newGroup);
                g.put("samples", (Long) g.get("samples") + Analytics.toLong(t.get("n")));
                g.put("threads", Math.max((Long) g.get("threads"), Analytics.toLong(t.get("threads"))));
                Map<String, Long> st = (Map<String, Long>) g.get("states");
                for (Map.Entry<String, Object> e : ((Map<String, Object>) t.get("st")).entrySet()) st.merge(e.getKey(), Analytics.toLong(e.getValue()), Long::sum);
            }
        }
        return groups.values();
    }

    private void staticFile(HttpExchange ex, String path) throws IOException {
        if (path.equals("/")) path = "/index.html";
        if (path.contains("..")) {
            send(ex, 400, "text/plain", new byte[0]);
            return;
        }
        try (InputStream in = Viewer.class.getResourceAsStream("/static" + path)) {
            if (in == null) {
                send(ex, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String type = path.endsWith(".html") ? "text/html; charset=utf-8" : path.endsWith(".js") ? "text/javascript; charset=utf-8"
                : path.endsWith(".css") ? "text/css; charset=utf-8" : "application/octet-stream";
            send(ex, 200, type, in.readAllBytes());
        }
    }

    private static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type.startsWith("application/json") ? "application/json; charset=utf-8" : type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
        if (body.length > 0) ex.getResponseBody().write(body);
    }

    private static long queryLong(Map<String, String> q, String k, long d) {
        try {
            return q.containsKey(k) ? Long.parseLong(q.get(k)) : d;
        } catch (NumberFormatException e) {
            return d;
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) m.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }
}
