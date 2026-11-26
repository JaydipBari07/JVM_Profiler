package io.profiler.agent;

import java.util.Arrays;

/**
 * One entry of a call tree: "method X, called from this parent". Calls of the same method from the same parent are
 * merged into a single node, so the node holds totals over all those calls.
 */
public final class Node {
    private static final Node[] NO_CHILDREN = new Node[0];

    public int methodId;
    public long callCount;
    /**
     * Sum of the durations of all merged calls (includes time spent in callees).
     */
    public long totalNanos;
    public long minNanos = Long.MAX_VALUE;
    public long maxNanos;
    /**
     * Sum of bytes allocated by all merged calls (includes callees, excludes the profiler's own allocations).
     */
    public long allocBytes;
    public int errorCount;
    public Node[] children = NO_CHILDREN;
    public int childCount;

    Node(int methodId) {
        this.methodId = methodId;
    }

    Node reset(int methodId) {
        this.methodId = methodId;
        callCount = totalNanos = allocBytes = maxNanos = 0;
        minNanos = Long.MAX_VALUE;
        errorCount = 0;
        childCount = 0;
        return this;
    }

    Node findChild(int methodId) {
        Node[] current = children;
        for (int i = 0, count = childCount; i < count; i++) {
            if (current[i].methodId == methodId) {
                return current[i];
            }
        }
        return null;
    }

    void addChild(Node child) {
        if (childCount == children.length) {
            children = Arrays.copyOf(children, childCount == 0 ? 4 : childCount * 2);
        }
        children[childCount++] = child;
    }

    /**
     * Record of a single method.
     * If method is called multiple times, the records are added to the existing node for that method.
     * callCount maintains the number of times that method is called.
     */
    void record(long durationNanos, long allocatedBytes, boolean failed) {
        callCount++;
        totalNanos += durationNanos;
        allocBytes += allocatedBytes;
        if (durationNanos < minNanos)
            minNanos = durationNanos;
        if (durationNanos > maxNanos)
            maxNanos = durationNanos;
        if (failed)
            errorCount++;
    }
}
