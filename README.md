# JVM Profiler

A lightweight profiler for JVM apps (Tomcat, Spring Boot, servlet). It records, per request and per background task, a
hierarchical call tree with **exact time and allocated bytes for every method** in the packages you choose, plus low-rate
stack sampling of all threads and a JVM metrics timeline. A separate viewer process provides a small web UI.

- `agent/`  : `profiler-agent.jar`, a `-javaagent` for the monitored JVM (Java 8+). ASM is shaded in.
- `viewer/` : `profiler-viewer.jar`, standalone web UI (Java 17+), no external dependencies, reads the agent's files.
- `demo/`   : embedded Tomcat app for functional testing.

## Documentation

[Master Guide](MASTER-GUIDE.md)

## Build

    mvn package

## Functional test with the demo app

    run-demo.cmd          (terminal 1: Tomcat on :8085 with agent, data in target\demo-data)
    run-viewer.cmd        (terminal 2: UI on http://localhost:8686)
    curl localhost:8085/orders/123        (generate requests; /orders/fail produces a 500)

The demo also runs a background job every 3 s (`report-scheduler-1`, ~120 ms) that shows up as a Background trace.

## Use on your application

Add to the application's JVM options (Tomcat: `CATALINA_OPTS` / `JAVA_OPTS`; Spring Boot: before `-jar`):

    -javaagent:/path/profiler-agent.jar=config=/path/profiler.properties

Minimum config (`profiler.properties`, see the file in this repo for every key):

    include=com.yourcompany.
    output.dir=/var/profiler-data
    app.name=orders-service

Then run the viewer anywhere that can read that directory:

    java -jar profiler-viewer.jar --dir /var/profiler-data --port 8686 [--bind 0.0.0.0 --token SECRET]

`--bind` to a non-loopback address is refused without `--token`.

### What is instrumented
- `include` (package prefixes, comma/semicolon separated): exact timing + allocation. Empty means nothing is instrumented except the entries below.
  `exclude` removes subpackages. `java.*`, `javax.*`, `jdk.*`, `sun.*`, CGLIB/proxy classes are always skipped. Needs an app restart to change.
- Always: Tomcat `ApplicationFilterChain.doFilter` and `HttpServlet.service` (javax + jakarta) as request roots, and JDBC `execute*`.
- All threads, regardless of packages: stack sampler (default 100 ms, auto-throttled) shown under "Sampled stacks".

### Overhead control
`include` scope, `trace.maxDepth`, `trace.maxNodes`, `trace.minDurationMicros`, `background.minMillis`, sampling interval, and an
automatic guard (`guard.*`) that pauses instrumentation if the estimated overhead exceeds `guard.maxOverheadPercent` of CPU capacity.
Data goes through a bounded queue; if it fills, traces are dropped (counted) rather than slowing the app.
Config changes marked dynamic in `profiler.properties` (including `enabled=false`) apply within `config.reloadSeconds` without restart.

### Output
NDJSON segments `profiler-<app>-<time>-p<pid>-<n>.ndjson` rotated at `segment.maxMB`, gzipped when closed, pruned by
`retention.maxTotalMB` / `retention.maxAgeHours`. `alerts.log` lists traces over `alert.slowMillis` / `alert.allocMB`.
Query strings are never captured; numeric/UUID path segments are masked to `{id}` (`url.maskNumericSegments`).

## Reading the numbers
- **Allocated** is bytes allocated by the thread inside the method (HotSpot `ThreadMXBean`), not retained heap.
- Identical call paths within one trace are merged: Calls = invocations; total/min/max/allocation are exact sums. Self = total minus children.
- **Wait ms** in the trace list = wall minus CPU (blocked, sleeping or waiting on I/O).
- Constructors, static initialisers and trivial getters/setters are not instrumented (`instrument.skipTrivial`).
- Sampling is safepoint-biased and estimates time as samples x interval.
- Request roots currently target Tomcat/servlet containers; Jetty/Undertow fall back to `HttpServlet.service`.

## License

This project is licensed under the [MIT License](LICENSE).
