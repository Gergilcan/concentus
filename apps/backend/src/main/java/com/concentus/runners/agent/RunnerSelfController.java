package com.concentus.runners.agent;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Whether this backend's own runner agent is set up and connected — what the desktop tray shows.
 * Reachable with the shell's launch token as well as a session (see {@code ShellTokenFilter}).
 */
@RestController
public class RunnerSelfController {

    private final EmbeddedRunnerAgent embedded;

    public RunnerSelfController(EmbeddedRunnerAgent embedded) {
        this.embedded = embedded;
    }

    @GetMapping("/api/runners/self")
    public RunnerAgent.Status self() {
        return embedded.status();
    }
}
