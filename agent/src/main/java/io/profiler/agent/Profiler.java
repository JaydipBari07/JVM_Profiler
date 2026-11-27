package io.profiler.agent;

import io.profiler.agent.output.Writer;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Runtime hooks invoked from instrumented bytecode. Must never throw into application code.
 */
public final class Profiler {
    private Profiler() {
    }

    private static final ThreadLocal<ThreadState> THREAD_STATE = new ThreadLocal<ThreadState>() {
        //ThreadState created only the first time THREAD_STATE variable is accessed.
        @Override
        protected ThreadState initialValue() {
            return new ThreadState(Thread.currentThread());
        }
    };
    public static final LongAdder CALLS = new LongAdder();
    public static volatile boolean active = true;
    static volatile Writer writer;
    public static volatile double pairCostNs = 250;
    public static final AtomicLong DROPPED_NODES = new AtomicLong();

    /** The shared call counter is only updated every 1024 calls per thread, to keep the hot path cheap. */
    private static final int CALL_BATCH_MASK = 1023;

    private static final ThreadMXBean TMX = ManagementFactory.getThreadMXBean();
    private static final MethodHandle ALLOC_CUR;
    private static final MethodHandle ALLOC_ID;
    private static final boolean CPU_OK;

    static {
        MethodHandle cur = null, byId = null;
        try {
            Class<?> c = Class.forName("com.sun.management.ThreadMXBean");
            if (c.isInstance(TMX)) {
                MethodHandles.Lookup l = MethodHandles.publicLookup();
                try {
                    Method m = c.getMethod("getCurrentThreadAllocatedBytes");       //Only works on new JDKs
                    cur = l.unreflect(m).bindTo(TMX);
                } catch (NoSuchMethodException e) {
                    Method m = c.getMethod("getThreadAllocatedBytes", long.class);  //Fallback: Works on old JDKs.
                    byId = l.unreflect(m).bindTo(TMX);
                }
            }
        } catch (Throwable ignored) {
        }
        ALLOC_CUR = cur;
        ALLOC_ID = byId;
        boolean cpu = false;
        try {
            cpu = TMX.isCurrentThreadCpuTimeSupported();
            if (cpu && !TMX.isThreadCpuTimeEnabled())
                TMX.setThreadCpuTimeEnabled(true);
        } catch (Throwable ignored) {
        }
        CPU_OK = cpu;
    }

    public static boolean allocSupported() {
        return ALLOC_CUR != null || ALLOC_ID != null;
    }

    /** Total bytes the current thread has allocated so far (a JVM-maintained counter), or 0 if unsupported. */
    private static long allocatedBytes(ThreadState state) {
        try {
            if (ALLOC_CUR != null) return (long) ALLOC_CUR.invokeExact();
            if (ALLOC_ID != null) return (long) ALLOC_ID.invokeExact(state.threadId);
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static long cpuTimeNanos() {
        try {
            return CPU_OK ? TMX.getCurrentThreadCpuTime() : 0;
        } catch (Throwable e) {
            return 0;
        }
    }

    /**
     * Inserted at the start of every instrumented method. If no trace is running on this thread, the call becomes the
     * root of a new "background" trace; otherwise it is recorded as a child of the call that is currently running.
     */
    public static void enter(int methodId) {
        try {
            ThreadState state = THREAD_STATE.get();
            if (state.ignoredOpenCalls > 0) {       //ignore all calls if profiler limit is reached
                state.ignoredOpenCalls++;
                return;
            }
            int depth = state.depth;
            if (depth == 0) {
                if (active) {
                    startTrace(state, methodId, null, null, Trace.BACKGROUND);
                }
                return;
            }
            if (!active) {
                state.ignoredOpenCalls++;
                return;
            }
            pushNode(state, methodId, depth);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Inserted at the start of HTTP request entry methods. Same as {@link #enter}, but when no trace is running it
     * starts a "request" trace and reads the method and URL from the request object.
     */
    public static void enterHttpRequest(int methodId, Object request, Object response) {
        try {
            ThreadState state = THREAD_STATE.get();
            if (state.ignoredOpenCalls > 0 || state.depth > 0) {
                enter(methodId);
                return;
            }
            if (active)
                startTrace(state, methodId, request, response, Trace.REQUEST);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Inserted at the start of JDBC execute methods that take the SQL text as their first parameter.
     * Same as {@link #enter}, but remembers the SQL on the call's tree node.
     */
    public static void enterSql(int methodId, String sql) {
        try {
            ThreadState state = THREAD_STATE.get();
            if (state.ignoredOpenCalls > 0) {
                state.ignoredOpenCalls++;
                return;
            }
            int depth = state.depth;
            if (depth == 0) {
                if (active) {
                    startTrace(state, methodId, null, null, Trace.BACKGROUND);
                    Node root = state.currentTrace.root;
                    if (root != null) root.sql = sql;
                }
                return;
            }
            if (!active) {
                state.ignoredOpenCalls++;
                return;
            }
            pushNode(state, methodId, depth, sql);
        } catch (Throwable ignored) {
        }
    }

    /** Inserted before every normal return of an instrumented method. */
    public static void exit(int methodId) {
        handleExit(methodId, false);
    }

    /** Inserted in the catch handler: the method ended by throwing {@code thrown}. */
    public static void exitError(Throwable thrown, int methodId) {
        try {
            ThreadState state = THREAD_STATE.get();
            if (state.depth == 1 && state.ignoredOpenCalls == 0) state.rootError = thrown;
        } catch (Throwable ignored) {
        }
        handleExit(methodId, true);
    }

    /** Inserted before every return of a request entry method; remembers the response so its status can be read. */
    public static void exitHttpRequest(int methodId, Object response) {
        try {
            ThreadState state = THREAD_STATE.get();
            if (state.depth == 1 && state.ignoredOpenCalls == 0) state.httpResponse = response;
        } catch (Throwable ignored) {
        }
        handleExit(methodId, false);
    }

    /**
     * Begins a new trace whose root call is {@code rootMethodId}. Note the two different clocks:
     * {@code startMillis} is wall-clock time (a date, shown in the UI as "when did this happen"), while the per-call
     * {@code startNanos} set in {@link #beginFrame} is a stopwatch with arbitrary origin, only useful for subtraction.
     */
    private static void startTrace(ThreadState state, int rootMethodId, Object request, Object response, int kind) {
        Trace trace = state.spareTrace;
        state.spareTrace = null;
        if (trace == null) trace = new Trace();
        trace.kind = kind;
        if (kind == Trace.REQUEST)
            RequestInfo.fillFromRequest(trace, request);
        Node rootNode = state.newNode(rootMethodId);
        trace.root = rootNode;
        trace.startMillis = System.currentTimeMillis();
        trace.cpuStartNanos = cpuTimeNanos();
        state.currentTrace = trace;
        state.nodeCount = 1;
        state.droppedCount = 0;
        state.ignoredOpenCalls = 0;
        state.rootError = null;
        state.httpResponse = null;
        beginFrame(state, 0, rootNode);
    }

    /**
     * Pushes a call onto the call stack at {@code level} and records its start values (stopwatch time, allocation
     * counter, profiler-allocation counter), which {@link #closeNode} later subtracts from the end values.
     */
    private static void beginFrame(ThreadState state, int level, Node node) {
        state.callStack[level] = node;
        state.startProfilerAllocBytes[level] = state.profilerAllocBytes;
        if ((++state.callCounter & CALL_BATCH_MASK) == 0)
            CALLS.add(CALL_BATCH_MASK + 1);
        state.depth = level + 1;
        state.startAllocBytes[level] = allocatedBytes(state);
        state.startNanos[level] = System.nanoTime();
    }

    /** Records a call made while a trace is running: finds or creates its tree node under the caller, then starts it. */
    private static void pushNode(ThreadState state, int methodId, int level) {
        pushNode(state, methodId, level, null);
    }

    private static void pushNode(ThreadState state, int methodId, int level, String sql) {
        if (level >= Config.maxDepth) {
            state.ignoredOpenCalls++;
            state.droppedCount++;
            return;
        }
        Node parent = state.callStack[level - 1];
        Node node = parent.findChild(methodId);
        if (level >= state.callStack.length || node == null) {
            if (node == null && state.nodeCount >= Config.maxNodes) {
                state.ignoredOpenCalls++;
                state.droppedCount++;
                return;
            }
            long allocBefore = allocatedBytes(state);
            if (node == null) {
                node = state.newNode(methodId);
                parent.addChild(node);
                state.nodeCount++;
            }
            if (level >= state.callStack.length) state.growStack();
            state.profilerAllocBytes += allocatedBytes(state) - allocBefore;
        }
        if (sql != null) node.sql = sql;
        beginFrame(state, level, node);
    }

    /**
     * Common end-of-call logic. The end time is read first thing, so the profiler's own bookkeeping below is not
     * counted as part of the application method's duration.
     */
    private static void handleExit(int methodId, boolean failed) {
        long endNanos = System.nanoTime();
        try {
            ThreadState state = THREAD_STATE.get();
            if (state.ignoredOpenCalls > 0) {
                state.ignoredOpenCalls--;
                return;
            }
            int depth = state.depth;
            if (depth == 0) return;
            long endAllocBytes = allocatedBytes(state);
            if (state.callStack[depth - 1].methodId != methodId) {
                // Exits did not match the top of the stack (e.g. a hook was skipped): close the unmatched calls as failed.
                int matchingLevel = depth - 1;
                while (matchingLevel >= 0 && state.callStack[matchingLevel].methodId != methodId) matchingLevel--;
                if (matchingLevel < 0) return;
                while (depth - 1 > matchingLevel) {
                    closeNode(state, depth - 1, endNanos, endAllocBytes, true);
                    depth--;
                }
            }
            closeNode(state, depth - 1, endNanos, endAllocBytes, failed);
        } catch (Throwable ignored) {
        }
    }

    /** Finishes the call at {@code level}: adds its duration and allocation to its tree node and pops it off the stack. */
    private static void closeNode(ThreadState state, int level, long endNanos, long endAllocBytes, boolean failed) {
        Node node = state.callStack[level];
        long profilerAllocDuringCall = state.profilerAllocBytes - state.startProfilerAllocBytes[level];
        long appAllocBytes = endAllocBytes - state.startAllocBytes[level] - profilerAllocDuringCall;
        node.record(endNanos - state.startNanos[level], appAllocBytes > 0 ? appAllocBytes : 0, failed);
        state.depth = level;
        if (level == 0) finishTrace(state, node, failed);
    }

    /** The root call has ended: complete the trace (total duration, CPU time, status...) and hand it to the writer. */
    private static void finishTrace(ThreadState state, Node rootNode, boolean failed) {
        Trace trace = state.currentTrace;
        state.currentTrace = null;
        Object response = state.httpResponse;
        Throwable thrown = state.rootError;
        state.httpResponse = null;
        state.rootError = null;
        state.ignoredOpenCalls = 0;
        if (trace == null) return;
        trace.durNanos = rootNode.totalNanos;
        if (trace.kind == Trace.BACKGROUND && (state.calibrating || rootNode.totalNanos < Config.bgMinNanos)) {
            state.recycle(rootNode);
            trace.reset();
            state.spareTrace = trace;
            return;
        }
        trace.cpuNanos = Math.max(0, cpuTimeNanos() - trace.cpuStartNanos);
        trace.alloc = rootNode.allocBytes;
        trace.err = failed;
        trace.error = thrown == null ? null : thrown.getClass().getName();
        trace.threadName = state.thread.getName();
        trace.tid = state.threadId;
        trace.nodeCount = state.nodeCount;
        trace.dropped = state.droppedCount;
        if (trace.kind == Trace.REQUEST) {
            trace.status = RequestInfo.responseStatus(response);
            if (thrown != null && trace.status < 400) trace.status = 500;
            if (trace.status >= 500) trace.err = true;
        }
        long slowLimitNanos = Config.alertSlowNanos, allocLimitBytes = Config.alertAllocBytes;
        trace.alert = (slowLimitNanos > 0 && trace.durNanos >= slowLimitNanos) || (allocLimitBytes > 0 && trace.alloc >= allocLimitBytes);
        if (state.droppedCount > 0) DROPPED_NODES.addAndGet(state.droppedCount);
        Writer traceWriter = writer;
        if (traceWriter == null || !traceWriter.submit(trace)) state.recycle(rootNode);
        else state.spareTrace = null;
        if (traceWriter == null) {
            trace.reset();
            state.spareTrace = trace;
        }
    }

    /**
     * Measures the real cost of one instrumented call (enter+exit) on this JVM.
     */
    public static void calibrate() {
        ThreadState state = THREAD_STATE.get();
        state.calibrating = true;
        int rootMethodId = MethodRegistry.register("io/profiler/Calibration", "root", "()V");
        int leafMethodId = MethodRegistry.register("io/profiler/Calibration", "leaf", "()V");
        boolean wasActive = active;
        active = true;
        long bestNanosPerCall = Long.MAX_VALUE;
        for (int round = 0; round < 5; round++) {
            int callsPerRound = 100_000;
            enter(rootMethodId);
            long roundStartNanos = System.nanoTime();
            for (int i = 0; i < callsPerRound; i++) {
                enter(leafMethodId);
                exit(leafMethodId);
            }
            long roundNanos = System.nanoTime() - roundStartNanos;
            exit(rootMethodId);
            bestNanosPerCall = Math.min(bestNanosPerCall, roundNanos / callsPerRound);
        }
        active = wasActive;
        state.calibrating = false;
        pairCostNs = Math.max(50, bestNanosPerCall);
        CALLS.reset();
    }
}
