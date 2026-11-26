package io.profiler.viewer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Aggregations over trace trees and sample trees. */
final class Analytics {
    private Analytics() {}

    static long toLong(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> kids(Map<String, Object> n) {
        Object k = n.get("k");
        return k == null ? List.of() : (List<Map<String, Object>>) k;
    }

    private static final class Hot {
        String name;
        long calls, total, self, alloc, selfAlloc, max, errors, traces;
    }

    static List<Map<String, Object>> hot(Store store, List<Store.Summary> sel, String sort, int limit) {
        Map<String, Hot> acc = new LinkedHashMap<>();
        store.loadTraces(sel, tr -> {
            Set<String> seenInTrace = new HashSet<>();
            @SuppressWarnings("unchecked")
            Map<String, Object> r = (Map<String, Object>) tr.get("root");
            walkHot(r, acc, new HashSet<>(), seenInTrace);
            for (String s : seenInTrace) acc.get(s).traces++;
        });
        List<Hot> list = new ArrayList<>(acc.values());
        Comparator<Hot> cmp = switch (sort) {
            case "self" -> Comparator.comparingLong((Hot h) -> h.self);
            case "alloc" -> Comparator.comparingLong((Hot h) -> h.alloc);
            case "selfAlloc" -> Comparator.comparingLong((Hot h) -> h.selfAlloc);
            case "calls" -> Comparator.comparingLong((Hot h) -> h.calls);
            default -> Comparator.comparingLong((Hot h) -> h.total);
        };
        list.sort(cmp.reversed());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Hot h : list.subList(0, Math.min(limit, list.size()))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", h.name);
            m.put("calls", h.calls);
            m.put("total", h.total);
            m.put("self", h.self);
            m.put("alloc", h.alloc);
            m.put("selfAlloc", h.selfAlloc);
            m.put("max", h.max);
            m.put("errors", h.errors);
            m.put("traces", h.traces);
            out.add(m);
        }
        return out;
    }

    private static void walkHot(Map<String, Object> n, Map<String, Hot> acc, Set<String> path, Set<String> seen) {
        String name = (String) n.get("n");
        Hot h = acc.computeIfAbsent(name, k -> {
            Hot x = new Hot();
            x.name = k;
            return x;
        });
        long t = toLong(n.get("t")), a = toLong(n.get("a")), ct = 0, ca = 0;
        List<Map<String, Object>> ks = kids(n);
        for (Map<String, Object> k : ks) {
            ct += toLong(k.get("t"));
            ca += toLong(k.get("a"));
        }
        boolean recursive = !path.add(name);
        h.calls += toLong(n.get("c"));
        h.self += Math.max(0, t - ct);
        h.selfAlloc += Math.max(0, a - ca);
        h.max = Math.max(h.max, toLong(n.get("mx")));
        h.errors += toLong(n.get("e"));
        if (!recursive) {
            h.total += t;
            h.alloc += a;
        }
        seen.add(name);
        for (Map<String, Object> k : ks) walkHot(k, acc, path, seen);
        if (!recursive) path.remove(name);
    }

    /** Merges trace trees by method name into one flame tree rooted at "all". */
    static Map<String, Object> flame(Store store, List<Store.Summary> sel) {
        Map<String, Object> root = node("all");
        store.loadTraces(sel, tr -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> r = (Map<String, Object>) tr.get("root");
            root.put("t", toLong(root.get("t")) + toLong(r.get("t")));
            root.put("a", toLong(root.get("a")) + toLong(r.get("a")));
            root.put("c", toLong(root.get("c")) + toLong(r.get("c")));
            mergeChild(root, r);
        });
        clean(root);
        return root;
    }

    private static Map<String, Object> node(String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("n", name);
        m.put("c", 0L);
        m.put("t", 0L);
        m.put("a", 0L);
        m.put("mx", 0L);
        m.put("e", 0L);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static void mergeChild(Map<String, Object> parent, Map<String, Object> from) {
        Map<String, Map<String, Object>> idx = (Map<String, Map<String, Object>>) parent.computeIfAbsent("_idx", x -> new TreeMap<String, Map<String, Object>>());
        Map<String, Object> c = idx.computeIfAbsent((String) from.get("n"), nm -> {
            Map<String, Object> cn = node(nm);
            ((List<Object>) parent.computeIfAbsent("k", x -> new ArrayList<>())).add(cn);
            return cn;
        });
        c.put("t", toLong(c.get("t")) + toLong(from.get("t")));
        c.put("a", toLong(c.get("a")) + toLong(from.get("a")));
        c.put("c", toLong(c.get("c")) + toLong(from.get("c")));
        c.put("mx", Math.max(toLong(c.get("mx")), toLong(from.get("mx"))));
        c.put("e", toLong(c.get("e")) + toLong(from.get("e")));
        for (Map<String, Object> k : kids(from)) mergeChild(c, k);
    }

    static void clean(Map<String, Object> n) {
        n.remove("_idx");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ks = (List<Map<String, Object>>) n.get("k");
        if (ks != null) for (Map<String, Object> k : ks) clean(k);
    }

    /** Merges sample trees of one thread group across sample records. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> samples(Store store, long from, long to, String group) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("f", group);
        root.put("n", 0L);
        Map<String, Long> states = new LinkedHashMap<>();
        long samples = 0, intervalMs = 100;
        for (Store.SampleRef r : store.samples(from, to)) {
            Map<String, Object> rec = store.loadSample(r);
            if (rec == null) continue;
            intervalMs = toLong(rec.get("intervalMs"));
            for (Map<String, Object> t : (List<Map<String, Object>>) rec.get("threads")) {
                if (!group.equals(t.get("g"))) continue;
                samples += toLong(t.get("n"));
                for (Map.Entry<String, Object> e : ((Map<String, Object>) t.get("st")).entrySet()) states.merge(e.getKey(), toLong(e.getValue()), Long::sum);
                mergeSample(root, (Map<String, Object>) t.get("tree"));
            }
        }
        cleanSample(root);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("group", group);
        out.put("samples", samples);
        out.put("intervalMs", intervalMs);
        out.put("states", states);
        out.put("tree", root);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void mergeSample(Map<String, Object> into, Map<String, Object> from) {
        into.put("n", toLong(into.get("n")) + toLong(from.get("n")));
        Object fk = from.get("k");
        if (fk == null) return;
        Map<String, Map<String, Object>> idx = (Map<String, Map<String, Object>>) into.computeIfAbsent("_idx", x -> new TreeMap<String, Map<String, Object>>());
        for (Map<String, Object> k : (List<Map<String, Object>>) fk) {
            Map<String, Object> c = idx.computeIfAbsent((String) k.get("f"), nm -> {
                Map<String, Object> cn = new LinkedHashMap<>();
                cn.put("f", nm);
                cn.put("n", 0L);
                ((List<Object>) into.computeIfAbsent("k", x -> new ArrayList<>())).add(cn);
                return cn;
            });
            mergeSample(c, k);
        }
    }

    @SuppressWarnings("unchecked")
    private static void cleanSample(Map<String, Object> n) {
        n.remove("_idx");
        List<Map<String, Object>> ks = (List<Map<String, Object>>) n.get("k");
        if (ks != null) for (Map<String, Object> k : ks) cleanSample(k);
    }

    /** Speedscope "evented" profile built from the merged call tree of a single trace. */
    static Map<String, Object> speedscope(Map<String, Object> trace) {
        List<Object> frames = new ArrayList<>();
        Map<String, Integer> idx = new LinkedHashMap<>();
        List<Object> events = new ArrayList<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) trace.get("root");
        long end = emitEvent(root, 0, frames, idx, events);
        Map<String, Object> prof = new LinkedHashMap<>();
        prof.put("type", "evented");
        prof.put("name", trace.get("title"));
        prof.put("unit", "nanoseconds");
        prof.put("startValue", 0L);
        prof.put("endValue", end);
        prof.put("events", events);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("$schema", "https://www.speedscope.app/file-format-schema.json");
        out.put("shared", Map.of("frames", frames));
        out.put("profiles", List.of(prof));
        out.put("activeProfileIndex", 0);
        out.put("exporter", "jvm-profiler");
        out.put("name", trace.get("title"));
        return out;
    }

    private static long emitEvent(Map<String, Object> n, long at, List<Object> frames, Map<String, Integer> idx, List<Object> events) {
        String name = (String) n.get("n");
        Integer fi = idx.get(name);
        if (fi == null) {
            fi = frames.size();
            idx.put(name, fi);
            frames.add(Map.of("name", name));
        }
        events.add(Map.of("type", "O", "frame", fi, "at", at));
        long cursor = at;
        for (Map<String, Object> k : kids(n)) cursor = emitEvent(k, cursor, frames, idx, events);
        long end = Math.max(at + toLong(n.get("t")), cursor);
        events.add(Map.of("type", "C", "frame", fi, "at", end));
        return end;
    }
}
