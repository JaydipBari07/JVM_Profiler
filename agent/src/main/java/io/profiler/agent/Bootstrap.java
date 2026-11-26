package io.profiler.agent;

import io.profiler.agent.instrument.Transformer;
import io.profiler.agent.monitor.Housekeeper;
import io.profiler.agent.monitor.Sampler;
import io.profiler.agent.output.Writer;

import java.lang.instrument.Instrumentation;

public final class Bootstrap {
    private static Writer writer;
    private static Housekeeper hk;
    private static Sampler sampler;

    private Bootstrap() {
    }

    public static void start(String args, Instrumentation inst) {
        Config.load(args);
        if (!Config.enabled)
            System.err.println("[profiler] starting disabled (enabled=false); can be switched on at runtime via config file");

        writer = new Writer();
        hk = new Housekeeper(writer);
        sampler = new Sampler(writer);

        Profiler.writer = writer;
        Profiler.active = Config.enabled;

        startComponents();

        inst.addTransformer(new Transformer(), false);
        Runtime.getRuntime().addShutdownHook(new Thread("profiler-shutdown") {
            @Override
            public void run() {
                Profiler.active = false;
                stopComponents();
            }
        });
        System.err.println("[profiler] agent started, output=" + Config.outputDir.getAbsolutePath() + ", include="
                + (Config.include.length == 0 ? "(none: entry points + JDBC + sampling only)" : String.join(",", Config.include)));
    }

    private static void startComponents() {
        writer.start();
        hk.start();
        sampler.start();
    }

    private static void stopComponents() {
        sampler.shutdown();
        hk.shutdown();
        writer.shutdown();
    }
}
