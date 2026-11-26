package io.profiler.agent.monitor;

import io.profiler.agent.Config;
import io.profiler.agent.Profiler;
import io.profiler.agent.output.Writer;
import io.profiler.agent.util.Json;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Low-frequency stack sampler covering every thread (including those not instrumented).
 */
public final class Sampler extends Thread {
    private static final int MAX_TREE_NODES = 4000;

    private static final class SNode {
        final String f;
        long n;
        final Map<String, SNode> kids = new HashMap<String, SNode>();

        SNode(String f) {
            this.f = f;
        }
    }

    private static final class Group {
        final SNode root = new SNode("");
        final Map<String, Long> states = new HashMap<String, Long>();
        final Set<Long> threads = new HashSet<Long>();
        long samples;
        int nodes;
    }

    private final ThreadMXBean tmx = ManagementFactory.getThreadMXBean();
    private final Writer writer;
    private Map<String, Group> groups = new HashMap<String, Group>();
    private long windowStart = System.currentTimeMillis();
    private volatile boolean stop;

    public Sampler(Writer w) {
        super("profiler-sampler");
        setDaemon(true);
        writer = w;
    }

    public void shutdown() {
        stop = true;
        interrupt();
    }

    @Override
    public void run() {
        long sleep = Config.samplingIntervalMs;
        while (!stop) {
            try {
                Thread.sleep(sleep);
                long t0 = System.nanoTime();
                if (Config.samplingEnabled && Profiler.active) sample();
                long costMs = (System.nanoTime() - t0) / 1_000_000;
                sleep = Math.max(Config.samplingIntervalMs, costMs * 19);
                if (System.currentTimeMillis() - windowStart >= Config.samplingFlushSec * 1000L) flush();
            } catch (InterruptedException e) {
                break;
            } catch (Throwable t) {
                sleep = 1000;
            }
        }
        flush();
    }

    private void sample() {
        long self = getId();
        ThreadInfo[] infos = tmx.getThreadInfo(tmx.getAllThreadIds(), Config.samplingMaxDepth);
        for (ThreadInfo ti : infos) {
            if (ti == null || ti.getThreadId() == self) continue;
            String name = ti.getThreadName();
            if (name.startsWith("profiler-")) continue;
            String g = threadGroup(name);
            Group grp = groups.get(g);
            if (grp == null) groups.put(g, grp = new Group());
            grp.samples++;
            grp.threads.add(ti.getThreadId());
            String st = ti.getThreadState().name();
            Long c = grp.states.get(st);
            grp.states.put(st, c == null ? 1 : c + 1);
            StackTraceElement[] stack = ti.getStackTrace();
            if (stack.length == 0 || isIdle(ti.getThreadState(), stack[0])) continue;
            SNode cur = grp.root;
            cur.n++;
            for (int i = stack.length - 1; i >= 0; i--) {
                String f = stack[i].getClassName() + "." + stack[i].getMethodName();
                SNode k = cur.kids.get(f);
                if (k == null) {
                    if (grp.nodes >= MAX_TREE_NODES) break;
                    cur.kids.put(f, k = new SNode(f));
                    grp.nodes++;
                }
                k.n++;
                cur = k;
            }
        }
    }

    private static boolean isIdle(Thread.State s, StackTraceElement top) {
        if (s == Thread.State.WAITING || s == Thread.State.TIMED_WAITING) {
            String c = top.getClassName();
            return c.startsWith("jdk.internal.misc.Unsafe") || c.startsWith("sun.misc.Unsafe") || c.equals("java.lang.Object")
                    || c.startsWith("java.util.concurrent.locks.") || c.equals("java.lang.Thread") && !top.getMethodName().equals("sleep");
        }
        String c = top.getClassName();
        return s == Thread.State.RUNNABLE && (c.startsWith("sun.nio.ch.") || c.startsWith("java.net.Socket") || c.contains("PlainSocketImpl"));
    }

    static String threadGroup(String name) {
        int e = name.length();
        while (e > 0 && Character.isDigit(name.charAt(e - 1))) e--;
        return e == 0 ? name : name.substring(0, e);
    }

    private void flush() {
        Map<String, Group> g = groups;
        groups = new HashMap<String, Group>();
        long from = windowStart, to = System.currentTimeMillis();
        windowStart = to;
        if (g.isEmpty()) return;
        StringBuilder sb = new StringBuilder(1024);
        sb.append("{\"t\":\"samples\",\"from\":").append(from).append(",\"ts\":").append(to).append(",\"intervalMs\":")
                .append(Config.samplingIntervalMs).append(",\"threads\":[");
        boolean first = true;
        for (Map.Entry<String, Group> e : g.entrySet()) {
            Group grp = e.getValue();
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"g\":");
            Json.writeEscapedString(sb, e.getKey());
            sb.append(",\"threads\":").append(grp.threads.size()).append(",\"n\":").append(grp.samples).append(",\"st\":{");
            boolean f2 = true;
            for (Map.Entry<String, Long> s : grp.states.entrySet()) {
                if (!f2) sb.append(',');
                f2 = false;
                sb.append('"').append(s.getKey()).append("\":").append(s.getValue());
            }
            sb.append("},\"tree\":");
            writeTree(sb, grp.root);
            sb.append('}');
        }
        sb.append("]}");
        writer.submit(sb.toString());
    }

    private void writeTree(StringBuilder sb, SNode n) {
        sb.append("{\"f\":");
        Json.writeEscapedString(sb, n.f);
        sb.append(",\"n\":").append(n.n);
        if (!n.kids.isEmpty()) {
            List<SNode> ks = new ArrayList<SNode>(n.kids.values());
            sb.append(",\"k\":[");
            for (int i = 0; i < ks.size(); i++) {
                if (i > 0) sb.append(',');
                writeTree(sb, ks.get(i));
            }
            sb.append(']');
        }
        sb.append('}');
    }
}
