package io.profiler.agent.util;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;

/** Reads CPU load from the JDK-specific {@code com.sun.management.OperatingSystemMXBean}, via reflection so it works on any JVM. */
public final class OsMetrics {
    private static final String OS_BEAN = "com.sun.management.OperatingSystemMXBean";

    private OsMetrics() {
    }

    public static boolean isSupported(String methodName) {
        try {
            Class.forName(OS_BEAN).getMethod(methodName);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** Returns the metric as a JSON number with 4 decimals, or "-1" if unavailable. */
    public static String read(String methodName) {
        try {
            Object os = ManagementFactory.getOperatingSystemMXBean();
            Method m = Class.forName(OS_BEAN).getMethod(methodName);
            double v = (Double) m.invoke(os);
            return Double.isNaN(v) || v < 0 ? "-1" : String.format("%.4f", v);
        } catch (Throwable e) {
            return "-1";
        }
    }
}
