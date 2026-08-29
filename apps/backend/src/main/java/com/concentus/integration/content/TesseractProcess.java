package com.concentus.integration.content;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs the Tesseract program once and captures what it said.
 *
 * <p>Not {@code CliProcess}, though it is the same shape, for two reasons that matter here.
 * Stdout and stderr are kept apart: stdout IS the OCR text, and Tesseract chatters on stderr
 * ("Estimating resolution as 300", a warning about a missing DPI tag) — merged, that chatter
 * would be read into the knowledge base as if it were on the page. And the image goes in on
 * stdin rather than through a temp file, so attachment bytes never touch the disk on their way
 * through; Tesseract 4 and later accept {@code stdin} as the input name.
 *
 * <p>Read as UTF-8, explicitly. Tesseract writes UTF-8 whatever the console's code page, and a
 * platform-default reader on a Spanish Windows (Cp1252) turns every accented letter of a scanned
 * invoice into two wrong ones — the bug this codebase has already met once in the CLI runners.
 */
final class TesseractProcess {

    private TesseractProcess() {
    }

    /** What one run produced. {@code exit} is {@code -1} when the process was killed on timeout. */
    record Result(int exit, String stdout, String stderr) {
        boolean ok() {
            return exit == 0;
        }
    }

    /** The seam: everything the extractor knows about the host goes through one of these. */
    @FunctionalInterface
    interface Runner {
        Result run(List<String> argv, byte[] stdin, int timeoutSec) throws IOException;
    }

    /**
     * Runs {@code argv}, feeding {@code stdin} (may be null), waiting at most {@code timeoutSec}.
     *
     * <p>Both output pipes are drained on their own threads, and the input is written on a third:
     * a process whose pipe fills while nobody reads it blocks forever, and Tesseract does not
     * start reading the image until it has parsed its arguments.
     */
    static Result run(List<String> argv, byte[] stdin, int timeoutSec) throws IOException {
        Process process = new ProcessBuilder(argv).start();
        try {
            CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> drain(process, true));
            CompletableFuture<String> err = CompletableFuture.supplyAsync(() -> drain(process, false));
            CompletableFuture.runAsync(() -> feed(process, stdin));

            if (!process.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("tesseract did not finish within " + timeoutSec + " s");
            }
            return new Result(process.exitValue(),
                    out.get(5, TimeUnit.SECONDS), err.get(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for tesseract", e);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IOException("could not read tesseract's output: " + e.getMessage(), e);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static String drain(Process process, boolean stdout) {
        try {
            byte[] bytes = (stdout ? process.getInputStream() : process.getErrorStream()).readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static void feed(Process process, byte[] stdin) {
        try (OutputStream in = process.getOutputStream()) {
            if (stdin != null) in.write(stdin);
        } catch (IOException e) {
            // The process closed its end early — because it failed, which the exit code reports,
            // or because it read everything it wanted, which is not our concern either way.
        }
    }
}
