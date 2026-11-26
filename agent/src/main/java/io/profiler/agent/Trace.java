package io.profiler.agent;

public final class Trace {
    public static final int REQUEST = 1, BACKGROUND = 2;

    public int kind;
    public Node root;
    public String method, uri, rid, threadName, error;
    public long tid, startMillis, durNanos, cpuNanos, alloc;
    long cpuStartNanos;     //used for calculating cpuNanos
    public int status = -1, nodeCount, dropped;
    public boolean err, alert;

    void reset() {
        kind = 0;
        root = null;
        method = uri = rid = threadName = error = null;
        tid = startMillis = durNanos = cpuStartNanos = cpuNanos = alloc = 0;
        status = -1;
        nodeCount = dropped = 0;
        err = alert = false;
    }
}
