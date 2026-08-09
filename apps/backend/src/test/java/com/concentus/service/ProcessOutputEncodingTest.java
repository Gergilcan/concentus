package com.concentus.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Agent output must be decoded as UTF-8, whatever the operating system's own charset is.
 *
 * <p>{@code Process.inputReader()} with no argument uses {@code native.encoding} — Cp1252 on a
 * Spanish Windows — while the CLI emits UTF-8. Nothing fails: accents, curly quotes and em dashes
 * just silently become mojibake in the run log, which is how it survived unnoticed. Note that
 * {@code file.encoding} being UTF-8 does not save this reader; it is a different property.
 *
 * <p>Uses a real subprocess emitting real UTF-8 bytes, because the bug lives in the decoding of a
 * pipe and a String round-trip would prove nothing about it.
 */
class ProcessOutputEncodingTest {

    private static final String ACCENTED = "año — “presupuesto” · ñandú";

    @Test
    void agentOutputWithAccentsSurvivesTheProcessPipe(@TempDir Path temp) throws Exception {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path file = temp.resolve("out.txt");
        Files.write(file, ACCENTED.getBytes(StandardCharsets.UTF_8));

        // A process that copies bytes to stdout untouched, so what arrives is exactly UTF-8.
        List<String> command = windows
                ? List.of("cmd", "/c", "type", file.toString().replace('/', '\\'))
                : List.of("cat", file.toString());
        Process process = new ProcessBuilder(command).start();

        try (BufferedReader reader = process.inputReader(StandardCharsets.UTF_8)) {
            assertThat(reader.readLine()).isEqualTo(ACCENTED);
        }
        process.waitFor();
    }

    @Test
    void theDefaultReaderIsTheBugThisGuardsAgainst(@TempDir Path temp) throws Exception {
        // Only meaningful where the OS charset is not UTF-8 — which is exactly where the bug bites.
        Charset osCharset = Charset.forName(System.getProperty("native.encoding", "UTF-8"));
        assumeThat(osCharset).as("an OS charset that is not UTF-8").isNotEqualTo(StandardCharsets.UTF_8);

        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path file = temp.resolve("out.txt");
        Files.write(file, ACCENTED.getBytes(StandardCharsets.UTF_8));
        List<String> command = windows
                ? List.of("cmd", "/c", "type", file.toString().replace('/', '\\'))
                : List.of("cat", file.toString());
        Process process = new ProcessBuilder(command).start();

        String decodedNatively;
        try (BufferedReader reader = process.inputReader()) {
            decodedNatively = reader.readLine();
        }
        process.waitFor();

        // Documents the failure rather than asserting an exact mojibake string, which would differ
        // per locale: the default reader mangles it, so the explicit charset above is load-bearing.
        assertThat(decodedNatively).isNotEqualTo(ACCENTED);
    }
}
