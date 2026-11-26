package io.profiler.agent.monitor;

import io.profiler.agent.Config;
import io.profiler.agent.Profiler;
import io.profiler.agent.output.Writer;
import io.profiler.agent.util.OsMetrics;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;

/**
 * Config reload, JVM metrics timeline and the overhead guard (adaptive kill switch).
 */
public final class Housekeeper extends Thread {
    private final Writer writer;
    private final MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
    private final int cores = Runtime.getRuntime().availableProcessors();
    private long lastCalls, lastTick = System.nanoTime(), lastGcCount, lastGcMs, lastDropped, trippedUntil;
    private int sec;
    private volatile boolean stop;

    public Housekeeper(Writer w) {
        super("profiler-housekeeper");
        setDaemon(true);
        writer = w;
    }

    public void shutdown() {
        stop = true;
        interrupt();
    }

    @Override
    public void run() {
        Profiler.calibrate();
        lastCalls = Profiler.CALLS.sum();
        lastTick = System.nanoTime();
        System.err.println("[profiler] calibrated instrumentation cost: " + (long) Profiler.pairCostNs + " ns/call, allocation tracking: "
                + (Profiler.allocSupported() ? "on" : "unavailable"));
        while (!stop) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
            try {
                sec++;
                if (sec % Config.reloadSec == 0) Config.reload(false);
                double pct = checkOverhead();
                Profiler.active = Config.enabled && System.currentTimeMillis() >= trippedUntil;
                if (sec % Config.metricsIntervalSec == 0) emitMetrics(pct);
            } catch (Throwable t) {
                System.err.println("[profiler] housekeeper: " + t);
            }
        }
    }

    private double checkOverhead() {
        long now = System.nanoTime(), calls = Profiler.CALLS.sum();
        double dt = (now - lastTick) / 1e9, rate = Math.max(0, calls - lastCalls) / dt;
        lastCalls = calls;
        lastTick = now;
        double pct = rate * Profiler.pairCostNs / 1e9 / cores * 100;
        if (Config.guardEnabled && pct > Config.guardMaxPercent && System.currentTimeMillis() >= trippedUntil && Config.enabled) {
            trippedUntil = System.currentTimeMillis() + Config.guardCooldownSec * 1000L;
            String msg = String.format("[profiler] estimated overhead %.1f%% > %.1f%% limit, instrumentation paused for %ds (sampling continues)",
                    pct, Config.guardMaxPercent, Config.guardCooldownSec);
            System.err.println(msg);
            writer.submit("{\"t\":\"event\",\"ts\":" + System.currentTimeMillis() + ",\"msg\":\"" + msg.replace("\"", "'") + "\"}");
        }
        return pct;
    }

    private void emitMetrics(double overheadPct) {
        MemoryUsage heap = mem.getHeapMemoryUsage(), non = mem.getNonHeapMemoryUsage();
        long gcCount = 0, gcMs = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCount += Math.max(0, gc.getCollectionCount());
            gcMs += Math.max(0, gc.getCollectionTime());
        }
        long dropped = writer.dropped.get();
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"t\":\"metrics\",\"ts\":").append(System.currentTimeMillis()).append(",\"heapUsed\":").append(heap.getUsed())
                .append(",\"heapMax\":").append(heap.getMax()).append(",\"heapCommitted\":").append(heap.getCommitted())
                .append(",\"nonHeap\":").append(non.getUsed()).append(",\"gcCount\":").append(gcCount - lastGcCount).append(",\"gcMs\":")
                .append(gcMs - lastGcMs).append(",\"threads\":").append(ManagementFactory.getThreadMXBean().getThreadCount())
                .append(",\"procCpu\":").append(OsMetrics.read("getProcessCpuLoad")).append(",\"sysCpu\":")
                .append(OsMetrics.read(OsMetrics.isSupported("getCpuLoad") ? "getCpuLoad" : "getSystemCpuLoad")).append(",\"overheadPct\":")
                .append(String.format("%.3f", overheadPct)).append(",\"dropped\":").append(dropped - lastDropped).append(",\"droppedNodes\":")
                .append(Profiler.DROPPED_NODES.get()).append(",\"active\":").append(Profiler.active).append('}');
        lastGcCount = gcCount;
        lastGcMs = gcMs;
        lastDropped = dropped;
        writer.submit(sb.toString());
    }
}
