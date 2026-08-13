package com.concentus.service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs a short-lived command-line tool and captures what it printed.
 *
 * <p>One implementation instead of the byte-identical copies {@link McpRegistry} and
 * {@link PluginRegistry} each kept: both drive the {@code claude} CLI for a list or a one-shot
 * mutation, and both need the same three things — merged stdout/stderr, a timeout that cannot hang
 * the request thread, and a failure reported as text rather than thrown.
 *
 * <p>Deliberately not for the agent processes: those stream stream-json for minutes and belong to
 * {@link LocalClaudeExecutor} and {@link FanoutExecutor}, which need the line-by-line output this
 * buffers away.
 */
final class CliProcess {

    private CliProcess() {
    }

    /** Exit code and merged output. An exit of {@code -1} means the process never reported one. */
    record Result(int exit, String output) {
    }

    /**
     * Runs {@code args}, waiting at most {@code timeoutSec} for it to finish.
     *
     * <p>Output is drained on another thread because a process whose pipe buffer fills blocks
     * forever when nobody reads it, and stdin is closed straight away so a tool that would prompt
     * sees end-of-input rather than waiting for a terminal that isn't there.
     */
    static Result run(List<String> args, int timeoutSec) {
        try {
            Process p = new ProcessBuilder(args).redirectErrorStream(true).start();
            p.getOutputStream().close();
            CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    return "";
                }
            });
            boolean done = p.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return new Result(-1, "timed out");
            }
            return new Result(p.exitValue(), out.get(3, TimeUnit.SECONDS));
        } catch (Exception e) {
            return new Result(-1, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /**
     * The line a failure should be reported by: the last one.
     *
     * <p>A CLI prints its banner and its progress first and the actual reason last, so the tail is
     * the part worth putting in front of a user. (The two copies of this were called
     * {@code firstLine} while already returning the last line.)
     */
    static String lastLine(String s) {
        if (s == null || s.isBlank()) return "unknown error";
        String[] lines = s.strip().split("\\R");
        return lines[lines.length - 1];
    }
}
