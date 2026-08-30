package com.concentus.runners.agent;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code java -jar concentus-backend.jar runner --url … --token …}: the runner on its own.
 *
 * <p>Flags win over the environment, and the environment is what a container sets. Exit codes:
 * {@code 2} usage, {@code 3} the hub refused the token (no retry), {@code 0} on SIGTERM.
 */
public final class RunnerAgentMain {

    static final String USAGE = """
            Usage: java -jar concentus-backend.jar runner --url <hub> --token <crn_…> [--name <name>]
                   [--data-dir <dir>] [--claude <path>] [--context-roots <a,b>] [--max-processes <n>]

            Environment: CONCENTUS_RUNNER_URL, CONCENTUS_RUNNER_TOKEN, CONCENTUS_RUNNER_NAME,
                         CONCENTUS_RUNNER_DATA_DIR (default ~/.concentus-runner), CLAUDE_COMMAND,
                         LOCAL_CONTEXT_ROOTS, EXECUTION_MAX_PROCESSES (default 4).
            The CLI's login is the machine's own, or CLAUDE_CODE_OAUTH_TOKEN from `claude setup-token`,
            or ANTHROPIC_API_KEY. The hub never receives it.
            """;

    private RunnerAgentMain() {
    }

    public static void main(String[] args) {
        Map<String, String> flags;
        try {
            flags = parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println(USAGE);
            System.exit(2);
            return;
        }
        String url = flags.getOrDefault("url", env("CONCENTUS_RUNNER_URL"));
        String token = flags.getOrDefault("token", env("CONCENTUS_RUNNER_TOKEN"));
        if (isBlank(url) || isBlank(token)) {
            System.err.println("A hub URL and a registration token are required.");
            System.err.println(USAGE);
            System.exit(2);
            return;
        }
        String name = flags.getOrDefault("name", env("CONCENTUS_RUNNER_NAME"));
        String dataDir = flags.getOrDefault("data-dir", env("CONCENTUS_RUNNER_DATA_DIR"));
        if (isBlank(dataDir)) dataDir = Path.of(System.getProperty("user.home", "."), ".concentus-runner").toString();
        String claude = flags.getOrDefault("claude", env("CLAUDE_COMMAND"));
        String roots = flags.getOrDefault("context-roots", env("LOCAL_CONTEXT_ROOTS"));
        int max = 4;
        String maxRaw = flags.getOrDefault("max-processes", env("EXECUTION_MAX_PROCESSES"));
        if (!isBlank(maxRaw)) {
            try {
                max = Integer.parseInt(maxRaw.trim());
            } catch (NumberFormatException e) {
                System.err.println("--max-processes must be a number.");
                System.exit(2);
                return;
            }
        }

        AgentRuntime runtime = RunnerAgent.runtime(Path.of(dataDir), claude, roots, max);
        RunnerAgent agent = new RunnerAgent(new RunnerAgent.Config(url, token, isBlank(name) ? null : name.trim(),
                version()), runtime);
        agent.declareCapacity(max);
        Runtime.getRuntime().addShutdownHook(new Thread(agent::stop, "runner-shutdown"));
        agent.start();
        try {
            agent.awaitStop();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.exit(agent.isFatal() ? 3 : 0);
    }

    /** {@code --key value} pairs; a flag without a value, or an unknown one, is a usage error. */
    static Map<String, String> parse(String[] args) {
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--")) throw new IllegalArgumentException("Unexpected argument: " + a);
            String key = a.substring(2);
            String value;
            int eq = key.indexOf('=');
            if (eq > 0) {
                value = key.substring(eq + 1);
                key = key.substring(0, eq);
            } else {
                if (i + 1 >= args.length) throw new IllegalArgumentException("--" + key + " needs a value.");
                value = args[++i];
            }
            switch (key) {
                case "url", "token", "name", "data-dir", "claude", "context-roots", "max-processes" -> out.put(key, value);
                default -> throw new IllegalArgumentException("Unknown flag: --" + key);
            }
        }
        return out;
    }

    /** The jar's own version, or what the environment says, or nothing. */
    static String version() {
        String env = env("CONCENTUS_APP_VERSION");
        if (!isBlank(env)) return env;
        Package p = RunnerAgentMain.class.getPackage();
        return p == null ? null : p.getImplementationVersion();
    }

    private static String env(String name) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? null : v;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
