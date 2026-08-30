package com.concentus.execution;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Spawns one CLI process. The one thing a unit test cannot do for real, and — since runners — the
 * one thing that may happen on another machine.
 *
 * <p>Two-argument form kept as the abstract method so the fake starters in tests, which have no
 * repositories, stay two-argument lambdas.
 */
public interface ProcessStarter {

    Process start(List<String> args, Path workdir) throws IOException;

    /** With extra environment — the push credentials of the process's checkouts. */
    default Process start(List<String> args, Path workdir, Map<String, String> env) throws IOException {
        return start(args, workdir);
    }

    /** The real thing: a child of this JVM, stderr folded into stdout as the executors expect. */
    static ProcessStarter local() {
        return new ProcessStarter() {
            @Override
            public Process start(List<String> args, Path workdir) throws IOException {
                return start(args, workdir, Map.of());
            }

            @Override
            public Process start(List<String> args, Path workdir, Map<String, String> env) throws IOException {
                ProcessBuilder pb = new ProcessBuilder(args).directory(workdir.toFile()).redirectErrorStream(true);
                pb.environment().putAll(env);
                return pb.start();
            }
        };
    }
}
