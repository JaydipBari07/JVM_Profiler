package io.profiler.agent;

import java.util.Arrays;

/**
 * Everything the profiler remembers about ONE application thread (kept in a ThreadLocal, so no locking is needed).
 *
 * The "call stack" is four parallel arrays indexed by nesting level: level 0 is the outermost instrumented call
 * (the root of a trace), level 1 is the call it made, and so on. {@link #depth} is how many levels are in use.
 */
final class ThreadState {
    private static final int POOL_MAX = 4096;
    private static final int INITIAL_STACK_SIZE = 24;

    final Thread thread;
    final long threadId;

    /** Per level: the tree node that this open call is being recorded into. */
    Node[] callStack = new Node[INITIAL_STACK_SIZE];
    /** Per level: System.nanoTime() when the call started. */
    long[] startNanos = new long[INITIAL_STACK_SIZE];
    /** Per level: bytes this thread had allocated (JVM counter) when the call started. */
    long[] startAllocBytes = new long[INITIAL_STACK_SIZE];
    /** Per level: value of {@link #profilerAllocBytes} when the call started. */
    long[] startProfilerAllocBytes = new long[INITIAL_STACK_SIZE];

    /** Number of levels of {@link #callStack} currently in use (0 = no trace is running on this thread). */
    int depth;
    /** Number of open calls that are NOT being recorded (too deep, too many nodes, or profiler paused); their exits are ignored. */
    int ignoredOpenCalls;
    /** Nodes created in the current trace, and calls that could not be recorded because of the limits. */
    int nodeCount, droppedCount;
    /** Bytes allocated by the profiler itself (new nodes, bigger arrays); subtracted so we only report the application's allocation. */
    long profilerAllocBytes;
    /** Instrumented calls made by this thread; periodically added to the global counter used by the overhead guard. */
    long callCounter;

    /** The trace being built on this thread, and a cleared Trace object kept for reuse. */
    Trace currentTrace, spareTrace;
    /** The HTTP response object (request traces only) and the exception that escaped the root call, remembered until the trace is finished. */
    Object httpResponse;
    Throwable rootError;
    boolean calibrating;

    private final Node[] nodePool = new Node[POOL_MAX];
    private int pooledNodeCount;

    ThreadState(Thread thread) {
        this.thread = thread;
        threadId = thread.getId();
    }

    void growStack() {
        int newSize = callStack.length * 2;
        callStack = Arrays.copyOf(callStack, newSize);
        startNanos = Arrays.copyOf(startNanos, newSize);
        startAllocBytes = Arrays.copyOf(startAllocBytes, newSize);
        startProfilerAllocBytes = Arrays.copyOf(startProfilerAllocBytes, newSize);
    }

    // Reuses nodes from the existing node pool so that no new node objects are created and garbage churn is avoided.
    Node newNode(int methodId) {
        return pooledNodeCount > 0 ? nodePool[--pooledNodeCount].reset(methodId) : new Node(methodId);
    }

    // Once trace is complete, it adds all the used node objects into nodePool for future reuse.
    // All traces inside single thread use a shared pool.
    void recycle(Node node) {
        for (int i = 0; i < node.childCount; i++)
            recycle(node.children[i]);
        if (pooledNodeCount < POOL_MAX)
            nodePool[pooledNodeCount++] = node;
    }
}
