package io.profiler.viewer;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiPredicate;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/** Incrementally indexes the agent's NDJSON segments (plain or gzipped) into an in-memory summary index. */
final class Store {
    record Summary(String id, String base, long offset, long start, long dur, long cpu, long alloc, String kind, String title,
                   String thread, String group, int status, boolean err, boolean alert, String rid, int nodes, String error, int dropped) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", id);
            m.put("start", start);
            m.put("dur", dur);
            m.put("cpu", cpu);
            m.put("alloc", alloc);
            m.put("kind", kind);
            m.put("title", title);
            m.put("thread", thread);
            m.put("group", group);
            m.put("status", status);
            m.put("err", err);
            m.put("alert", alert);
            m.put("rid", rid);
            m.put("nodes", nodes);
            m.put("error", error);
            m.put("dropped", dropped);
            return m;
        }
    }

    record SampleRef(String base, long offset, long from, long ts) {}

    private final Path dir;
    private final Map<String, Long> processed = new HashMap<>();
    private final Map<String, Map<Integer, String>> defs = new HashMap<>();
    private final List<Summary> traces = new ArrayList<>();
    private final Map<String, Summary> byId = new HashMap<>();
    private final List<SampleRef> samples = new ArrayList<>();
    private final List<Map<String, Object>> metrics = new ArrayList<>();
    private final List<Map<String, Object>> events = new ArrayList<>();

    Store(Path dir) {
        this.dir = dir;
    }

    synchronized void refresh() {
        TreeMap<String, Path> files = new TreeMap<>();
        try (Stream<Path> st = Files.list(dir)) {
            st.forEach(p -> {
                String n = p.getFileName().toString();
                if (!n.startsWith("profiler-")) return;
                if (n.endsWith(".ndjson")) files.put(n.substring(0, n.length() - 7), p);
                else if (n.endsWith(".ndjson.gz")) files.putIfAbsent(n.substring(0, n.length() - 10), p);
            });
        } catch (IOException e) {
            return;
        }
        for (Map.Entry<String, Path> e : files.entrySet()) {
            String base = e.getKey();
            Path p = e.getValue();
            boolean gz = p.getFileName().toString().endsWith(".gz");
            long from = processed.getOrDefault(base, 0L);
            try {
                long end = scanLines(p, gz, from, (off, line) -> {
                    index(base, off, line);
                    return true;
                });
                processed.put(base, end);
            } catch (IOException ignored) {
            }
        }
        if (processed.keySet().removeIf(b -> !files.containsKey(b))) {
            traces.removeIf(s -> !files.containsKey(s.base()));
            byId.values().removeIf(s -> !files.containsKey(s.base()));
            samples.removeIf(s -> !files.containsKey(s.base()));
            defs.keySet().removeIf(b -> !files.containsKey(b));
        }
    }

    private void index(String base, long off, byte[] line) {
        String s = new String(line, StandardCharsets.UTF_8);
        try {
            if (s.startsWith("{\"t\":\"trace\"")) {
                Map<String, Object> m = Json.parseObject(s);
                String thread = getString(m, "thread");
                Summary sm = new Summary(getString(m, "id"), base, off, getLong(m, "start"), getLong(m, "dur"), getLong(m, "cpu"), getLong(m, "alloc"), getString(m, "kind"),
                    getString(m, "title"), thread, threadGroup(thread), (int) getLong(m, "status", -1), m.containsKey("err"), m.containsKey("alert"), getString(m, "rid"),
                    (int) getLong(m, "nodes"), getString(m, "error"), (int) getLong(m, "dropped"));
                if (byId.putIfAbsent(sm.id(), sm) == null) traces.add(sm);
            } else if (s.startsWith("{\"t\":\"m\"")) {
                Map<String, Object> m = Json.parseObject(s);
                defs.computeIfAbsent(base, k -> new HashMap<>()).put((int) getLong(m, "id"), getString(m, "n"));
            } else if (s.startsWith("{\"t\":\"samples\"")) {
                Map<String, Object> m = Json.parseObject(s);
                samples.add(new SampleRef(base, off, getLong(m, "from"), getLong(m, "ts")));
            } else if (s.startsWith("{\"t\":\"metrics\"")) {
                metrics.add(Json.parseObject(s));
            } else if (s.startsWith("{\"t\":\"event\"")) {
                events.add(Json.parseObject(s));
            }
        } catch (RuntimeException ignored) {
        }
    }

    static String threadGroup(String name) {
        int e = name.length();
        while (e > 0 && Character.isDigit(name.charAt(e - 1))) e--;
        return e == 0 ? name : name.substring(0, e);
    }

    static String getString(Map<String, Object> m, String k) {
        Object o = m.get(k);
        return o == null ? null : o.toString();
    }

    static long getLong(Map<String, Object> m, String k) {
        return getLong(m, k, 0);
    }

    static long getLong(Map<String, Object> m, String k, long d) {
        Object o = m.get(k);
        return o instanceof Number n ? n.longValue() : d;
    }

    synchronized List<Summary> traces() {
        return new ArrayList<>(traces);
    }

    synchronized List<Map<String, Object>> metrics(long from, long to) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : metrics) {
            long ts = getLong(m, "ts");
            if (ts >= from && ts <= to) out.add(m);
        }
        return out;
    }

    synchronized List<Map<String, Object>> events(long from, long to) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : events) {
            long ts = getLong(m, "ts");
            if (ts >= from && ts <= to) out.add(m);
        }
        return out;
    }

    synchronized List<SampleRef> samples(long from, long to) {
        List<SampleRef> out = new ArrayList<>();
        for (SampleRef s : samples) if (s.ts() >= from && s.from() <= to) out.add(s);
        return out;
    }

    synchronized int[] counts() {
        return new int[]{traces.size(), samples.size(), metrics.size()};
    }

    private synchronized Map<Integer, String> defsOf(String base) {
        return defs.getOrDefault(base, Map.of());
    }

    private Path resolve(String base) {
        Path raw = dir.resolve(base + ".ndjson");
        return Files.exists(raw) ? raw : dir.resolve(base + ".ndjson.gz");
    }

    /** Reads full trace records for the given summaries (grouped per segment so each file is scanned once); names are resolved. */
    void loadTraces(List<Summary> sel, java.util.function.Consumer<Map<String, Object>> sink) {
        Map<String, List<Summary>> byBase = new HashMap<>();
        for (Summary s : sel) byBase.computeIfAbsent(s.base(), k -> new ArrayList<>()).add(s);
        for (Map.Entry<String, List<Summary>> e : byBase.entrySet()) {
            Set<Long> want = new HashSet<>();
            long min = Long.MAX_VALUE, max = 0;
            for (Summary s : e.getValue()) {
                want.add(s.offset());
                min = Math.min(min, s.offset());
                max = Math.max(max, s.offset());
            }
            Path p = resolve(e.getKey());
            Map<Integer, String> d = defsOf(e.getKey());
            long maxOff = max;
            try {
                scanLines(p, p.getFileName().toString().endsWith(".gz"), min, (off, line) -> {
                    if (want.contains(off)) {
                        Map<String, Object> m = Json.parseObject(new String(line, StandardCharsets.UTF_8));
                        nameNodes(m.get("root"), d);
                        sink.accept(m);
                    }
                    return off < maxOff;
                });
            } catch (IOException ignored) {
            }
        }
    }

    Map<String, Object> loadTrace(String id) {
        Summary s;
        synchronized (this) {
            s = byId.get(id);
        }
        if (s == null) return null;
        List<Map<String, Object>> out = new ArrayList<>();
        loadTraces(List.of(s), out::add);
        return out.isEmpty() ? null : out.get(0);
    }

    Map<String, Object> loadSample(SampleRef r) {
        List<Map<String, Object>> out = new ArrayList<>();
        Path p = resolve(r.base());
        try {
            scanLines(p, p.getFileName().toString().endsWith(".gz"), r.offset(), (off, line) -> {
                out.add(Json.parseObject(new String(line, StandardCharsets.UTF_8)));
                return false;
            });
        } catch (IOException ignored) {
        }
        return out.isEmpty() ? null : out.get(0);
    }

    @SuppressWarnings("unchecked")
    private static void nameNodes(Object node, Map<Integer, String> defs) {
        Map<String, Object> n = (Map<String, Object>) node;
        Object id = n.remove("m");
        n.put("n", id instanceof Number num ? defs.getOrDefault(num.intValue(), "method#" + num) : "?");
        Object k = n.get("k");
        if (k instanceof List<?> l) for (Object c : l) nameNodes(c, defs);
    }

    /** Scans complete lines starting at decompressed offset; handler returns false to stop. Returns offset after last complete line. */
    private static long scanLines(Path p, boolean gz, long from, BiPredicate<Long, byte[]> h) throws IOException {
        try (InputStream raw = Files.newInputStream(p);
             InputStream in = new BufferedInputStream(gz ? new GZIPInputStream(raw, 1 << 16) : raw, 1 << 16)) {
            long skipped = 0;
            while (skipped < from) {
                long n = in.skip(from - skipped);
                if (n <= 0) {
                    if (in.read() < 0) return skipped;
                    n = 1;
                }
                skipped += n;
            }
            long off = from;
            ByteArrayOutputStream line = new ByteArrayOutputStream(4096);
            int b;
            while ((b = in.read()) >= 0) {
                if (b == '\n') {
                    byte[] bytes = line.toByteArray();
                    long lineOff = off;
                    off += bytes.length + 1;
                    line.reset();
                    if (bytes.length > 0 && !h.test(lineOff, bytes)) return off;
                } else line.write(b);
            }
            return off;
        } catch (java.io.EOFException | java.util.zip.ZipException e) {
            return from;
        }
    }
}
