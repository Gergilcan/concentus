package com.concentus.runners.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Stand-ins for the {@code claude} CLI, as scripts the test writes: one that prints given lines,
 * one that sleeps, and one that speaks enough {@code stream-json} for a run to complete. Real
 * scripts rather than mocks, because what is under test is a process — spawned, streamed, killed.
 */
final class FakeCli {

    private FakeCli() {
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** The command line that runs {@code script}. */
    static List<String> command(Path script) {
        return windows() ? List.of("cmd.exe", "/c", script.toString()) : List.of("sh", script.toString());
    }

    static List<String> echoing(Path dir, String... lines) throws IOException {
        StringBuilder body = new StringBuilder();
        for (String line : lines) body.append(windows() ? "echo " + line : "printf '%s\\n' '" + line + "'").append('\n');
        return command(write(dir, body.toString()));
    }

    static List<String> sleeping(Path dir, int seconds) throws IOException {
        String body = windows() ? "ping -n " + (seconds + 1) + " 127.0.0.1 >nul\n" : "sleep " + seconds + "\n";
        return command(write(dir, body));
    }

    /**
     * A claude that answers {@code --version} and otherwise says hello in stream-json — enough for
     * the stream handler to record an agent message and a result, and for the run to complete.
     * Written as a file the {@code claude} command can point at directly.
     */
    static Path streamJson(Path dir, String answer) throws IOException {
        String init = "{\"type\":\"system\",\"subtype\":\"init\",\"model\":\"fake\",\"tools\":[]}";
        String message = "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"" + answer
                + "\"}],\"usage\":{\"input_tokens\":3,\"output_tokens\":5}}}";
        String result = "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"" + answer
                + "\",\"usage\":{\"input_tokens\":3,\"output_tokens\":5}}";
        String body;
        if (windows()) {
            body = "@echo off\r\n"
                    + "if \"%~1\"==\"--version\" (\r\n  echo fake-claude 1.0.0\r\n  exit /b 0\r\n)\r\n"
                    + "echo " + init + "\r\n"
                    + "echo " + message + "\r\n"
                    + "echo " + result + "\r\n";
            Path script = dir.resolve("fake-claude-" + UUID.randomUUID().toString().substring(0, 8) + ".cmd");
            Files.writeString(script, body, StandardCharsets.US_ASCII);
            return script;
        }
        body = "#!/bin/sh\n"
                + "if [ \"$1\" = \"--version\" ]; then echo 'fake-claude 1.0.0'; exit 0; fi\n"
                + "printf '%s\\n' '" + init + "'\n"
                + "printf '%s\\n' '" + message + "'\n"
                + "printf '%s\\n' '" + result + "'\n";
        Path script = dir.resolve("fake-claude-" + UUID.randomUUID().toString().substring(0, 8) + ".sh");
        Files.writeString(script, body, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return script;
    }

    private static Path write(Path dir, String body) throws IOException {
        Path script = dir.resolve("fake-" + UUID.randomUUID().toString().substring(0, 8) + (windows() ? ".cmd" : ".sh"));
        List<String> lines = new ArrayList<>();
        if (windows()) lines.add("@echo off");
        lines.add(body);
        Files.writeString(script, String.join(windows() ? "\r\n" : "\n", lines), StandardCharsets.UTF_8);
        return script;
    }
}
