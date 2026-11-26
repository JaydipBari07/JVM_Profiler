package io.profiler.agent.output;

import io.profiler.agent.Config;
import io.profiler.agent.MethodRegistry;
import io.profiler.agent.Node;
import io.profiler.agent.Trace;
import io.profiler.agent.util.FileUtil;
import io.profiler.agent.util.Json;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Single background thread that serialises traces/samples/metrics to rotating NDJSON segments.
 */
public final class Writer extends Thread {
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final ArrayBlockingQueue<Object> queue = new ArrayBlockingQueue<Object>(4096);
    public final AtomicLong dropped = new AtomicLong();
    final AtomicLong written = new AtomicLong();
    private final File dir;
    private final String prefix;
    private final String idPrefix;
    private final Set<Integer> defined = new HashSet<Integer>();
    private final List<Integer> ids = new ArrayList<Integer>();
    private OutputStream out;
    private File current;
    private long curBytes, lastFlush, lastRetention;
    private int seq;
    private volatile boolean stop;

    public Writer() {
        super("profiler-writer");
        setDaemon(true);
        dir = Config.outputDir.getAbsoluteFile();
        String pid = ManagementFactory.getRuntimeMXBean().getName();
        int at = pid.indexOf('@');
        if (at > 0) pid = pid.substring(0, at);
        prefix = "profiler-" + Config.appName + "-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + "-p" + pid;
        idPrefix = pid + "x" + Long.toString(System.currentTimeMillis(), 36) + "-";
        dir.mkdirs();
    }

    public boolean submit(Object o) {
        if (queue.offer(o)) return true;
        dropped.incrementAndGet();
        return false;
    }

    public void shutdown() {
        stop = true;
        interrupt();
        try {
            join(3000);
        } catch (InterruptedException ignored) {
        }
    }

    @Override
    public void run() {
        try {
            while (!stop) {
                Object o = null;
                try {
                    o = queue.poll(500, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    break;
                }
                writeEvent(o);
                long now = System.currentTimeMillis();
                if (now - lastFlush > 1000) flush();
                if (now - lastRetention > 600_000L) retention();
            }
            Object o;
            while ((o = queue.poll()) != null) writeEvent(o);
        } finally {
            close();
        }
    }

    private void writeEvent(Object o) {
        if (o == null) return;
        try {
            if (o instanceof Trace) writeTrace((Trace) o);
            else writeRawLine(String.valueOf(o));
        } catch (Throwable t) {
            System.err.println("[profiler] write failed: " + t);
        }
    }

    private void writeTrace(Trace t) throws IOException {
        ids.clear();
        StringBuilder tree = new StringBuilder(256);
        writeNode(tree, t.root, Config.minDurationNanos);
        StringBuilder sb = new StringBuilder(tree.length() + 256);
        String title = t.kind == Trace.REQUEST
                ? (t.method + " " + (t.uri.isEmpty() ? "/" : t.uri))
                : MethodRegistry.methodName(t.root.methodId);
        sb.append("{\"t\":\"trace\",\"id\":\"").append(idPrefix).append(Long.toString(written.get(), 36)).append("\",\"kind\":\"")
                .append(t.kind == Trace.REQUEST ? "request" : "background").append("\",\"title\":");
        Json.writeEscapedString(sb, title);
        sb.append(",\"thread\":");
        Json.writeEscapedString(sb, t.threadName);
        sb.append(",\"tid\":").append(t.tid).append(",\"start\":").append(t.startMillis).append(",\"dur\":").append(t.durNanos)
                .append(",\"cpu\":").append(t.cpuNanos).append(",\"alloc\":").append(t.alloc).append(",\"nodes\":").append(t.nodeCount)
                .append(",\"dropped\":").append(t.dropped);
        if (t.kind == Trace.REQUEST) {
            sb.append(",\"status\":").append(t.status);
            if (t.rid != null) {
                sb.append(",\"rid\":");
                Json.writeEscapedString(sb, t.rid);
            }
        }
        if (t.err) sb.append(",\"err\":true");
        if (t.error != null) {
            sb.append(",\"error\":");
            Json.writeEscapedString(sb, t.error);
        }
        if (t.alert) sb.append(",\"alert\":true");
        sb.append(",\"root\":").append(tree).append('}');
        ensureOpen();
        for (Integer id : ids) {
            if (defined.add(id)) {
                StringBuilder d = new StringBuilder(96).append("{\"t\":\"m\",\"id\":").append(id).append(",\"n\":");
                Json.writeEscapedString(d, MethodRegistry.methodName(id));
                write(d.append("}\n").toString());
            }
        }
        write(sb.append('\n').toString());
        written.incrementAndGet();
        if (t.alert) alert(t, title);
    }

    private void writeNode(StringBuilder sb, Node node, long minNs) {
        ids.add(node.methodId);
        sb.append("{\"m\":").append(node.methodId).append(",\"c\":").append(node.callCount).append(",\"t\":").append(node.totalNanos)
                .append(",\"a\":").append(node.allocBytes).append(",\"mn\":").append(node.callCount == 0 ? 0 : node.minNanos)
                .append(",\"mx\":").append(node.maxNanos);
        if (node.errorCount > 0) sb.append(",\"e\":").append(node.errorCount);
        if (node.childCount > 0) {
            sb.append(",\"k\":[");
            boolean first = true;
            for (int i = 0; i < node.childCount; i++) {
                Node child = node.children[i];
                if (child.callCount == 0 || child.totalNanos < minNs) continue;
                if (!first) sb.append(',');
                first = false;
                writeNode(sb, child, minNs);
            }
            sb.append(']');
        }
        sb.append('}');
    }

    private void writeRawLine(String json) throws IOException {
        ensureOpen();
        write(json + "\n");
    }

    private void alert(Trace t, String title) {
        try (FileWriter fw = new FileWriter(new File(dir, "alerts.log"), true)) {
            fw.write(new Date() + " ALERT " + title + " thread=" + t.threadName + " dur=" + t.durNanos / 1_000_000 + "ms alloc="
                    + t.alloc / 1024 + "KB status=" + t.status + "\n");
        } catch (IOException ignored) {
        }
    }

    private void ensureOpen() throws IOException {
        if (out != null && curBytes < Config.segmentMaxBytes) return;
        close();
        current = new File(dir, prefix + "-" + (seq++) + ".ndjson");
        out = new BufferedOutputStream(new FileOutputStream(current, true), 1 << 16);
        curBytes = 0;
        defined.clear();
    }

    private void write(String s) throws IOException {
        byte[] b = s.getBytes(UTF8);
        out.write(b);
        curBytes += b.length;
    }

    private void flush() {
        lastFlush = System.currentTimeMillis();
        try {
            if (out != null) out.flush();
        } catch (IOException ignored) {
        }
    }

    private void close() {
        if (out == null) return;
        File f = current;
        try {
            out.close();
        } catch (IOException ignored) {
        }
        out = null;
        FileUtil.gzip(f);
        retention();
    }

    private void retention() {
        lastRetention = System.currentTimeMillis();
        File[] files = dir.listFiles();
        if (files == null) return;
        List<File> segs = new ArrayList<File>();
        for (File f : files) {
            String n = f.getName();
            if (n.startsWith("profiler-") && (n.endsWith(".ndjson") || n.endsWith(".ndjson.gz")) && !f.equals(current))
                segs.add(f);
        }
        FileUtil.sortByLastModified(segs);
        long now = System.currentTimeMillis(), total = current != null ? curBytes : 0;
        for (File f : segs) total += f.length();
        for (File f : segs) {
            boolean old = now - f.lastModified() > Config.retentionMaxAgeMs;
            if (old || total > Config.retentionMaxBytes) {
                total -= f.length();
                f.delete();
            }
        }
    }
}
