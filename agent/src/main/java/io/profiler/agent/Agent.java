package io.profiler.agent;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.jar.JarFile;

/**
 * Entry point: -javaagent:profiler-agent.jar[=config=/path/profiler.properties,key=value,...]
 * The jar is appended to the bootstrap class path so instrumented classes in any class loader can see the runtime hooks;
 * all real work therefore lives in {@link Bootstrap}, which is loaded by the bootstrap loader.
 */
public final class Agent {
    private Agent() {
    }

    public static void premain(String args, Instrumentation inst) {
        try {
            File jar = new File(Agent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            inst.appendToBootstrapClassLoaderSearch(new JarFile(jar));
            Bootstrap.start(args, inst);
        } catch (Throwable t) {
            System.err.println("[profiler] failed to start, application continues unprofiled: " + t);
        }
    }
}
