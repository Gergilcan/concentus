package com.concentus.execution;

import com.concentus.config.AgentSpec.RepoSpec;
import com.concentus.git.GitWorkspace;
import com.concentus.service.ContextFolderResolver;
import com.concentus.service.ProcessCeiling;
import com.concentus.support.LocalClaudeSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * This machine as a run host — what every run had before runners existed, behind the interface.
 */
@Component
public class LocalRunHost implements RunHost {

    public static final String ID = "local";

    private final LocalClaudeSupport support;
    private final ContextFolderResolver contextFolders;
    private final GitWorkspace git;
    private final ProcessCeiling ceiling;
    private final ProcessStarter starter;
    private final int serverPort;

    @Autowired
    public LocalRunHost(LocalClaudeSupport support, ContextFolderResolver contextFolders, GitWorkspace git,
                        ProcessCeiling ceiling, @Value("${server.port:8734}") int serverPort) {
        this(support, contextFolders, git, ceiling, ProcessStarter.local(), serverPort);
    }

    /** With the spawn injectable — the seam the fan-out tests drive fake processes through. */
    public LocalRunHost(LocalClaudeSupport support, ContextFolderResolver contextFolders, GitWorkspace git,
                        ProcessCeiling ceiling, ProcessStarter starter, int serverPort) {
        this.support = support;
        this.contextFolders = contextFolders;
        this.git = git;
        this.ceiling = ceiling == null ? ProcessCeiling.unlimited() : ceiling;
        this.starter = starter;
        this.serverPort = serverPort;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "this machine";
    }

    @Override
    public boolean isLocal() {
        return true;
    }

    @Override
    public Optional<String> command() {
        return support == null ? Optional.empty() : support.command();
    }

    @Override
    public ProcessCeiling ceiling() {
        return ceiling;
    }

    @Override
    public String toolsBaseUrl() {
        return "http://127.0.0.1:" + serverPort;
    }

    @Override
    public List<String> resolveContextDirs(List<String> requested, BiConsumer<String, String> onRejected) {
        if (contextFolders == null) return List.of();
        return contextFolders.resolve(requested, onRejected).stream().map(Path::toString).toList();
    }

    @Override
    public String readClaudeMd(String raw, BiConsumer<String, String> onRejected) {
        if (contextFolders == null) return null;
        Path file = contextFolders.resolveClaudeMd(raw, onRejected);
        if (file == null) return null;
        try {
            return Files.readString(file);
        } catch (IOException e) {
            onRejected.accept(raw, "could not be read: " + e.getMessage());
            return null;
        }
    }

    @Override
    public List<GitWorkspace.Checkout> prepareClones(List<RepoSpec> repos, Path workdir) {
        return git == null ? List.of() : git.prepare(repos, workdir);
    }

    @Override
    public String headOf(Path checkout) {
        return git == null ? null : git.headOf(checkout);
    }

    @Override
    public String patchOf(Path checkout) {
        return git == null ? null : git.patchOf(checkout);
    }

    @Override
    public String patchSince(Path checkout, String base) throws IOException {
        if (git == null) throw new IOException("git is not configured");
        return git.patchSince(checkout, base);
    }

    @Override
    public String patchDirectory(Path checkout) {
        return checkout == null ? null : checkout.toAbsolutePath().normalize().toString();
    }

    @Override
    public Process start(List<String> args, Path workdir, Map<String, String> env) throws IOException {
        return starter.start(args, workdir, env);
    }
}
