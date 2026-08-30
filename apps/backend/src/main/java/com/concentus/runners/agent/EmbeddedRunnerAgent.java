package com.concentus.runners.agent;

import com.concentus.git.GitWorkspace;
import com.concentus.service.ContextFolderResolver;
import com.concentus.service.ProcessCeiling;
import com.concentus.support.LocalClaudeSupport;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * This backend as a runner for another one — the desktop's "also a runner" mode.
 *
 * <p>Off unless {@code concentus.runner.url} and {@code concentus.runner.token} are set. Then the
 * agent connects to that hub with this backend's own CLI, context roots, git and process ceiling,
 * and executes what it is handed alongside everything the local backend keeps doing.
 */
@Component
public class EmbeddedRunnerAgent {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedRunnerAgent.class);

    private final RunnerAgent agent;
    private final String url;

    public EmbeddedRunnerAgent(LocalClaudeSupport support, ContextFolderResolver contextFolders, GitWorkspace git,
                               ProcessCeiling ceiling,
                               @Value("${concentus.runner.url:}") String url,
                               @Value("${concentus.runner.token:}") String token,
                               @Value("${concentus.runner.name:}") String name,
                               @Value("${app.data-dir}") String dataDir,
                               @Value("${app.version:}") String version) {
        this.url = url == null ? "" : url.trim();
        boolean configured = !this.url.isBlank() && token != null && !token.isBlank();
        this.agent = configured
                ? new RunnerAgent(new RunnerAgent.Config(this.url, token.trim(),
                        name == null || name.isBlank() ? null : name.trim(),
                        version == null || version.isBlank() ? null : version),
                        new AgentRuntime(Path.of(dataDir, "runner"), support, contextFolders, git, ceiling))
                : null;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (agent == null) return;
        log.info("This backend is also a runner for {}.", url);
        agent.start();
    }

    @PreDestroy
    public void stop() {
        if (agent != null) agent.stop();
    }

    public RunnerAgent.Status status() {
        if (agent == null) return new RunnerAgent.Status(false, false, null, null, null);
        return agent.status();
    }
}
