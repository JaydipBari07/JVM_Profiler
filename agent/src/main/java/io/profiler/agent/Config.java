package io.profiler.agent;

import static io.profiler.agent.util.PropertyParser.parseBool;
import static io.profiler.agent.util.PropertyParser.parseDouble;
import static io.profiler.agent.util.PropertyParser.parseList;
import static io.profiler.agent.util.PropertyParser.parseLong;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;

public final class Config {
    public static volatile boolean enabled = true;
    public static volatile boolean samplingEnabled = true;
    public static volatile int samplingIntervalMs = 100;
    public static volatile long minDurationNanos = 0;
    public static volatile long bgMinNanos = 100_000_000L;
    public static volatile long alertSlowNanos = 0;
    public static volatile long alertAllocBytes = 0;
    public static volatile boolean guardEnabled = true;
    public static volatile double guardMaxPercent = 10;
    public static volatile int guardCooldownSec = 60;
    public static volatile int maxDepth = 100;
    public static volatile int maxNodes = 5000;

    public static volatile String[] include = new String[0];
    public static volatile String[] exclude = new String[0];
    public static volatile boolean jdbc = true;
    public static volatile boolean skipTrivial = true;
    public static volatile boolean maskNumeric = true;
    public static volatile String[] idHeaders = {"X-Request-ID", "X-B3-TraceId", "traceparent"};

    public static File outputDir = new File("profiler-data");
    public static String appName = "app";
    public static int samplingFlushSec = 30;
    public static int samplingMaxDepth = 64;
    public static int metricsIntervalSec = 10;
    public static long segmentMaxBytes = 50L << 20;
    public static long retentionMaxBytes = 500L << 20;
    public static long retentionMaxAgeMs = 48L * 3600_000L;
    public static int reloadSec = 5;

    private static File file;
    private static long fileModified;
    private static Properties overrides = new Properties();

    private static final String[] DEFAULT_EXCLUDE = {
            "java.", "javax.", "jdk.", "sun.", "com.sun.", "io.profiler.", "org.objectweb.asm.", "kotlin.", "scala."
    };

    public static void load(String agentArgs) {
        Properties args = new Properties();
        if (agentArgs != null) {
            for (String kv : agentArgs.split(",")) {
                int i = kv.indexOf('=');
                if (i > 0) args.setProperty(kv.substring(0, i).trim(), kv.substring(i + 1).trim());
            }
        }
        overrides = args;
        String path = args.getProperty("config", System.getProperty("profiler.config"));
        if (path == null && new File("profiler.properties").isFile()) path = "profiler.properties";
        if (path != null) file = new File(path);
        reload(true);
    }

    public static void reload(boolean force) {
        Properties p = new Properties();
        if (file != null && file.isFile()) {
            long m = file.lastModified();
            if (!force && m == fileModified) return;
            fileModified = m;
            try (InputStream in = new FileInputStream(file)) {
                p.load(in);
            } catch (Exception e) {
                System.err.println("[profiler] cannot read config " + file + ": " + e);
            }
        } else if (!force) return;
        for (Map.Entry<Object, Object> e : System.getProperties().entrySet()) {
            String k = String.valueOf(e.getKey());
            if (k.startsWith("profiler.") && !k.equals("profiler.config"))
                p.setProperty(k.substring(9), String.valueOf(e.getValue()));
        }
        for (String k : overrides.stringPropertyNames())
            if (!k.equals("config")) p.setProperty(k, overrides.getProperty(k));
        apply(p, force);
    }

    private static void apply(Properties p, boolean initial) {
        enabled = parseBool(p, "enabled", true);
        samplingEnabled = parseBool(p, "sampling.enabled", true);
        samplingIntervalMs = Math.max(10, (int) parseLong(p, "sampling.intervalMs", 100));
        minDurationNanos = parseLong(p, "trace.minDurationMicros", 0) * 1000L;
        bgMinNanos = parseLong(p, "background.minMillis", 100) * 1_000_000L;
        alertSlowNanos = parseLong(p, "alert.slowMillis", 0) * 1_000_000L;
        alertAllocBytes = (long) (parseDouble(p, "alert.allocMB", 0) * 1048576L);
        guardEnabled = parseBool(p, "guard.enabled", true);
        guardMaxPercent = parseLong(p, "guard.maxOverheadPercent", 10);
        guardCooldownSec = (int) parseLong(p, "guard.cooldownSeconds", 60);
        maxDepth = (int) Math.max(2, Math.min(1000, parseLong(p, "trace.maxDepth", 100)));
        maxNodes = (int) Math.max(10, parseLong(p, "trace.maxNodes", 5000));
        include = parseList(p.getProperty("include"), new String[0]);
        exclude = parseList(p.getProperty("exclude"), new String[0]);
        jdbc = parseBool(p, "instrument.jdbc", true);
        skipTrivial = parseBool(p, "instrument.skipTrivial", true);
        maskNumeric = parseBool(p, "url.maskNumericSegments", true);
        idHeaders = parseList(p.getProperty("request.idHeaders"), new String[]{"X-Request-ID", "X-B3-TraceId", "traceparent"});
        if (initial) {
            outputDir = new File(p.getProperty("output.dir", "profiler-data"));
            appName = p.getProperty("app.name", "app").replaceAll("[^A-Za-z0-9_.-]", "_");
            samplingFlushSec = (int) Math.max(5, parseLong(p, "sampling.flushSeconds", 30));
            samplingMaxDepth = (int) Math.max(8, parseLong(p, "sampling.maxDepth", 64));
            metricsIntervalSec = (int) Math.max(1, parseLong(p, "metrics.intervalSeconds", 10));
            segmentMaxBytes = (long) (parseDouble(p, "segment.maxMB", 50) * 1048576L);
            retentionMaxBytes = (long) (parseDouble(p, "retention.maxTotalMB", 500) * 1048576L);
            retentionMaxAgeMs = parseLong(p, "retention.maxAgeHours", 48) * 3600_000L;
            reloadSec = (int) Math.max(1, parseLong(p, "config.reloadSeconds", 5));
        }
    }

    public static boolean included(String internalName) {
        String[] inc = include;
        if (inc.length == 0) return false;
        String n = internalName.replace('/', '.');
        if (n.contains("$$") || n.contains("$Proxy") || n.contains("CGLIB$")) return false;
        for (String x : DEFAULT_EXCLUDE) if (n.startsWith(x)) return false;
        for (String x : exclude) if (n.startsWith(x)) return false;
        for (String x : inc) if (n.startsWith(x)) return true;
        return false;
    }

}
