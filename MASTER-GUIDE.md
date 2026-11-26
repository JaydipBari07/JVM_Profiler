# Java Profiler - Guide

## 1. What it is

A lightweight profiler for JVM applications (Tomcat, Spring Boot, servlet containers). For every
**HTTP request** and notable **background task** it records a hierarchical call tree with the exact
**time and allocated bytes** of each method in the packages you choose. It also samples thread stacks
at a low rate and records a JVM metrics timeline. A separate viewer process provides a web UI.

Components (Maven multi-module build):

| Module | Purpose | Requirements |
|---|---|---|
| `agent` | `profiler-agent.jar` — `-javaagent` inside the monitored JVM. ASM is shaded; no runtime dependencies. | Java 8+ (HotSpot) |
| `viewer` | `profiler-viewer.jar` — standalone web UI reading the agent's output files. JDK `HttpServer` + vanilla JS only. | Java 17+ |
| `demo` | Embedded Tomcat app for functional testing. | — |

## 2. Working principle

The agent uses **bytecode instrumentation**. When the JVM loads a class, the agent's
`ClassFileTransformer` rewrites each method of your selected packages using ASM:

```java
// original
Order load(String id) { ...body... }

// what the JVM actually runs
Order load(String id) {
    Profiler.enter(7);                       // 7 = id of "OrderService.load(String)"
    try { ...body...; Profiler.exit(7); }    // before every return
    catch (Throwable t) { Profiler.exitError(t, 7); throw t; }
}
```

Key points:

- **Your source is never modified** — only the bytecode in memory. A class that fails to transform is left untouched.
- **What gets instrumented:** classes matching `include` prefixes (minus `exclude`), plus always: Tomcat servlet entry points (`ApplicationFilterChain.doFilter`, `HttpServlet.service`) as request roots, and JDBC `execute*` methods. JDK classes, constructors, static initialisers, native/abstract methods and (optionally) trivial getters/setters are skipped.
- **Runtime accounting:** each thread keeps a stack of tree nodes (`ThreadState`). Calls are **merged by call path** — repeated calls to the same method under the same parent update one node (`count`, total/min/max time, allocated bytes, errors), so memory is bounded by distinct call paths, not call count. Totals are exact sums.
- **Time** comes from `System.nanoTime()`; **allocated bytes** from `ThreadMXBean` (bytes allocated, not retained heap). The profiler subtracts its own allocation "debt" from enclosing frames.
- **Self time** = total time minus the sum of children.

### Trace roots

| Kind | Starts when | Title | Notes |
|---|---|---|---|
| Request | First instrumented call at a servlet entry point | `GET /orders/{id}` | Records status, request id header, CPU time, exception class. Numeric/UUID path segments masked as `{id}`; query strings never captured |
| Background | Outermost instrumented call on any other thread | Root method name | Kept only if ≥ `background.minMillis` (default 100 ms) |

Wall time minus CPU time is shown as **Wait** (blocking / I/O / sleeping).

### Supporting threads

| Thread | Role |
|---|---|
| `profiler-writer` | Serialises finished traces, samples and metrics to NDJSON segment files; rotates, gzips, applies retention. Application threads hand traces to a bounded queue (4096) and never block on I/O |
| `profiler-sampler` | Every ~100 ms snapshots all thread stacks (covers code outside `include`). Self-throttled to ~5% duty cycle |
| `profiler-housekeeper` | Startup calibration, config reload, JVM metrics every 10 s, overhead guard |

### Overhead guard (adaptive kill switch)

At startup the agent measures the real cost of one instrumented call (~130 ns on the dev machine).
Every second it estimates overhead; if it exceeds `guard.maxOverheadPercent` (default 10%),
instrumentation pauses for `guard.cooldownSeconds` (default 60 s) while sampling continues.

### Data flow

```
your app --(ASM adds hooks)--> Profiler.enter/exit --> Trace (merged call tree)
      --> bounded queue --> Writer --> output.dir/*.ndjson(.gz) --> Viewer (web UI, port 8686)
```

The agent and viewer are coupled only by the data directory — the viewer can run on any machine that
can read it, and can be restarted at any time. Output is newline-delimited JSON segments
(`profiler-<app>-<timestamp>-p<pid>-<n>.ndjson`, gzipped on rotation), plus `alerts.log` for traces
exceeding alert thresholds. Record types: `m` (method definition), `trace`, `samples`, `metrics`, `event`.

## 3. Setup

### Prerequisites

- Build: JDK 17 + Maven
- Monitored app: Java 8+, HotSpot JVM
- Viewer: Java 17+

### Build

```
mvn package
# agent/target/profiler-agent.jar
# viewer/target/profiler-viewer.jar
# demo/target/profiler-demo.jar
```

Copy the agent jar and config to a stable folder so a rebuild doesn't replace it under a running app.

### Attach the agent

Add one JVM option — it coexists with existing flags:

```
-javaagent:C:\path\to\profiler-agent.jar=config=C:\path\to\profiler.properties
```

- **IntelliJ**: VM options field of the run configuration
- **Tomcat**: `CATALINA_OPTS` / `JAVA_OPTS` (`setenv.bat`/`setenv.sh`)
- **Spring Boot jar**: place before `-jar`

### Minimal `profiler.properties`

```properties
app.name=myapp
output.dir=C:/profiler-data
include=com.yourcompany.
```

Notes:
- Use **forward slashes** in paths (backslash is an escape character in `.properties`).
- Keep the **trailing dot** in `include` prefixes. Empty `include` means only request entry points + JDBC.
- Config priority (low→high): properties file → `-Dprofiler.<key>` system properties → agent arguments (`key=value` comma-separated).
- Most keys are **dynamic**: re-read every `config.reloadSeconds` (default 5) without restart — including `enabled=false` to switch the profiler off live. `include`/`exclude` changes only affect classes loaded afterwards (restart for full effect).

### Key settings

| Key | Default | Description |
|---|---|---|
| `include` / `exclude` | empty | Package prefixes to instrument / skip — the main overhead control |
| `instrument.jdbc` | true | Time JDBC `execute*` methods |
| `instrument.skipTrivial` | true | Skip getters/setters/toString etc. |
| `enabled` | true | Master switch |
| `output.dir` | ./profiler-data | Data directory the viewer must read |
| `trace.maxDepth` / `trace.maxNodes` | 100 / 5000 | Per-trace limits (excess counted as dropped) |
| `trace.minDurationMicros` | 0 | Prune faster nodes when writing (time folds into parent self time) |
| `background.minMillis` | 100 | Min duration for background traces |
| `sampling.enabled` / `sampling.intervalMs` | true / 100 | Stack sampler |
| `alert.slowMillis` / `alert.allocMB` | 0 (off) | Write slow/alloc-heavy traces to `alerts.log` and flag in UI |
| `guard.enabled` / `guard.maxOverheadPercent` | true / 10 | Overhead kill switch |
| `segment.maxMB` / `retention.maxTotalMB` / `retention.maxAgeHours` | 50 / 500 / 48 | Rotation and retention |

To time library calls exactly (e.g. `RestTemplate`), extend `include`:
`include=com.yourcompany.,org.springframework.web.client.` — otherwise they only appear in sampled stacks.

### Run the viewer

```
java -jar profiler-viewer.jar --dir C:\profiler-data --port 8686
# open http://localhost:8686
```

Arguments: `--dir` (default `./profiler-data`), `--port` (8686), `--bind` (127.0.0.1; non-loopback requires `--token`), `--token` (shared secret, or env `PROFILER_TOKEN`), `--refresh` (index refresh seconds).
The viewer binds to localhost by default and has **no TLS** — use a reverse proxy or SSH tunnel if exposing it.

### Verify with the demo

`run-demo.cmd` starts an embedded Tomcat app (port 8085) with the agent attached, writing to `target\demo-data`; `run-viewer.cmd` starts the viewer. Generate traffic: `curl localhost:8085/orders/123` (or `/orders/fail` for a 500). A ~120 ms background job runs every 3 s.

## 4. Using the viewer

### Filter bar

Time range (5 min–48 h or custom), type (all/requests/background), search (URL, thread, request id),
minimum ms, errors only, alerts only, auto-refresh.

### Tabs

| Tab | Shows |
|---|---|
| **Traces** | List with duration, CPU, wait, allocation, status. Click a row to expand the hierarchical call tree (calls, total/self ms, allocation total/self, avg/min/max, errors). Heaviest path auto-expanded; Speedscope export available |
| **Threads** | Per thread group: traces, time, allocation, errors, sampled states |
| **Hot methods** | Aggregate per-method table sortable by total, self, allocation or calls |
| **Flame graph** | Call trees merged across traces, by time or allocation; click to zoom |
| **Sampled stacks** | Merged busy stacks per thread group — covers code outside `include` |
| **JVM metrics** | Heap, GC, CPU, threads, estimated profiler overhead, dropped traces, profiler events |

JDBC calls appear as `...PreparedStatement.executeQuery()` nodes in Traces, Hot methods and the Flame graph.

### Reading the numbers

- **Allocated** = bytes allocated by the thread inside the method, *not* retained heap.
- **Calls** = invocations at that call path; identical paths merge (exact sum of times/allocations; order of repeated calls is lost).
- **Total** includes children; **Self** excludes them. **Wait** = wall − CPU.
- Instrumentation cost (calculated during calibration) is inside measured times — very short, very frequent methods look slightly slower than they are.
- Sampled-stack times are estimates (samples × interval), and sampling is safepoint-biased.
- Windows CPU time has ~15 ms granularity, so short requests can show 0 CPU.

## 5. Recommended rollout

1. Start with `include` limited to your own packages; test on a non-production instance under realistic load.
2. Watch the **JVM metrics** tab: profiler self-overhead, dropped traces, CPU, GC.
3. Tune: `exclude` noisy packages, set `trace.minDurationMicros`, raise `background.minMillis`, lengthen `sampling.intervalMs`.
4. Keep `guard.enabled=true` so instrumentation pauses itself if cost gets too high.
5. Kill switch: set `enabled=false` in the config file (applies within seconds) or remove the `-javaagent` line.

## 6. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `failed to start, application continues unprofiled` | Read the message after it — usually a bad agent jar path or unwritable `output.dir` |
| No traces appear | Check `include` (trailing dot), viewer reads the same `output.dir`, time range covers activity. Traces appear ~1 s after flush |
| Request roots with few children | `include` doesn't cover that code — add the prefix and restart |
| Viewer `BindException` | Another viewer holds the port — stop it or use `--port` |
| "estimated overhead ... paused" | Guard tripped — narrow `include`, add `exclude`, or raise `guard.maxOverheadPercent` |
| Wrong output path | Use forward slashes in the properties file |
| JVM class-data-sharing warning | Cosmetic — ignore |
| Viewer slow with lots of data | Aggregations cap at 3000 traces; narrow the time range or lower `retention.*` |

## 7. Limitations

- Memory figures are allocation, not retained heap.
- Constructors/static initialisers not instrumented; classes loaded before the agent starts are not retransformed.
- Merged call paths lose ordering of repeated calls (Speedscope layout is synthetic).
- Request roots target Tomcat; other containers fall back to `HttpServlet.service`. Async/reactive work moved across threads is not stitched into one trace.
- JDBC covers only classes directly implementing the statement interfaces; SQL text is not captured.
- Query strings, headers (except configured request-id headers) and bodies are never captured — but thread names, URL paths and request ids are stored, so restrict access to the data directory.
- The overhead guard is an estimate, not a measured CPU share. Overall throughput overhead has not been benchmarked yet.
