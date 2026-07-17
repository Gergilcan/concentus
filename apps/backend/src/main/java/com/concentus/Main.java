package com.concentus;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.concentus.config.AgentSpec;
import com.concentus.config.ConfigLoader;
import com.concentus.runner.AgentRunner;
import com.concentus.runner.AgentRunnerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Entry point.
 *
 * <pre>
 *   java -jar target/concentus-backend.jar &lt;config.yaml&gt; [prompt]
 * </pre>
 *
 * If no prompt argument is given, the prompt is read from stdin.
 * Requires ANTHROPIC_API_KEY (or an `ant auth login` profile) in the environment.
 */
public final class Main {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java -jar concentus-backend.jar <config.yaml> [prompt]");
            System.err.println("       (if [prompt] is omitted, it is read from stdin)");
            System.exit(2);
        }

        try {
            AgentSpec spec = ConfigLoader.load(Path.of(args[0]));
            String prompt = args.length > 1 ? args[1] : readStdin();
            if (prompt == null || prompt.isBlank()) {
                System.err.println("No prompt provided (pass as an argument or via stdin).");
                System.exit(2);
            }

            AnthropicClient client = AnthropicOkHttpClient.fromEnv();
            AgentRunner runner = AgentRunnerFactory.create(spec, client);
            runner.run(prompt);

        } catch (IllegalArgumentException e) {
            // Validation / config errors — user-facing, no stack trace.
            System.err.println(e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static String readStdin() {
        try {
            byte[] bytes = System.in.readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return null;
        }
    }

    private Main() {}
}
