package com.concentus.runners;

import com.concentus.execution.RunHost;
import com.concentus.git.RepoExpander;
import com.concentus.runners.protocol.Frame;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The runners connected right now.
 *
 * <p>The table says which runners exist; this says which are here. One connection per runner id —
 * a second connection replaces the first, which is closed and told so, because a runner
 * restarted after a crash reconnects before the hub has noticed the old socket is dead, and two
 * of them would each take half the frames. A runner that has not heart-beaten for
 * {@link #SILENCE_MS} is closed by the sweep and its runs told.
 */
@Component
public class RunnerRegistry {

    private static final Logger log = LoggerFactory.getLogger(RunnerRegistry.class);

    /** Three missed heartbeats. */
    static final long SILENCE_MS = 45_000;
    /** How often {@code last_seen_at} is written per runner. */
    static final long TOUCH_INTERVAL_MS = 60_000;

    /** What the socket knows about a runner, for the roster. */
    public record Live(boolean online, int busy, Integer capacity, String hostname, String os, String arch,
                       String version, String claudeVersion, String authKind, Long connectedAt, String hubUrl,
                       String name) {
        public static final Live OFFLINE = new Live(false, 0, null, null, null, null, null, null, null, null,
                null, null);
    }

    private final RunnerStore store;
    private final ObjectMapper mapper;
    private final Path mirrorRoot;
    private final RepoExpander expander;
    private final String publicUrl;
    private final Map<String, RunnerConnection> connections = new ConcurrentHashMap<>();
    private final Map<String, RemoteRunHost> hosts = new ConcurrentHashMap<>();
    private final Map<String, Long> touched = new ConcurrentHashMap<>();

    @Autowired
    public RunnerRegistry(RunnerStore store, ObjectMapper mapper, RepoExpander expander,
                          @Value("${app.data-dir}") String dataDir,
                          @Value("${app.public-url:}") String publicUrl) {
        this(store, mapper, expander, Path.of(dataDir, "local"), publicUrl);
    }

    RunnerRegistry(RunnerStore store, ObjectMapper mapper, RepoExpander expander, Path mirrorRoot, String publicUrl) {
        this.store = store;
        this.mapper = mapper;
        this.expander = expander;
        this.mirrorRoot = mirrorRoot;
        this.publicUrl = publicUrl == null ? "" : publicUrl;
    }

    /** A runner that has said hello. */
    public void connect(RunnerConnection connection) {
        String id = connection.runnerId();
        RunnerConnection previous = connections.put(id, connection);
        hosts.put(id, new RemoteRunHost(connection, mapper, mirrorRoot, expander, publicUrl));
        if (previous != null && previous != connection) {
            previous.close("replaced by a new connection from the same runner");
        }
        touch(id, true);
        Frame.Hello h = connection.hello();
        log.info("Runner '{}' connected ({}, {}/{}, claude {}, {}).", connection.runnerName(),
                h == null ? "?" : h.hostname(), h == null ? "?" : h.os(), h == null ? "?" : h.arch(),
                h == null ? "?" : h.claudeVersion(), h == null ? "?" : h.authKind());
    }

    public void disconnect(RunnerConnection connection) {
        String id = connection.runnerId();
        if (connections.remove(id, connection)) {
            hosts.remove(id);
            log.info("Runner '{}' disconnected.", connection.runnerName());
        }
        connection.close("closed");
    }

    public void heartbeat(RunnerConnection connection) {
        touch(connection.runnerId(), false);
    }

    /** The host for a runner, while it is connected. */
    public Optional<RunHost> hostFor(String runnerId) {
        RunnerConnection c = runnerId == null ? null : connections.get(runnerId);
        if (c == null || !c.isOpen()) return Optional.empty();
        return Optional.ofNullable(hosts.get(runnerId));
    }

    public boolean online(String runnerId) {
        return hostFor(runnerId).isPresent();
    }

    public Live live(String runnerId) {
        RunnerConnection c = runnerId == null ? null : connections.get(runnerId);
        if (c == null || !c.isOpen()) return Live.OFFLINE;
        Frame.Hello h = c.hello();
        return new Live(true, c.reportedBusy(), h == null ? null : h.capacity(), h == null ? null : h.hostname(),
                h == null ? null : h.os(), h == null ? null : h.arch(), h == null ? null : h.version(),
                h == null ? null : h.claudeVersion(), h == null ? null : h.authKind(), c.connectedAt(),
                h == null ? null : h.hubUrl(), c.runnerName());
    }

    public List<String> onlineIds() {
        List<String> out = new ArrayList<>();
        connections.forEach((id, c) -> {
            if (c.isOpen()) out.add(id);
        });
        return out;
    }

    /** A revoked token's connection is closed at once: the row says it may no longer execute. */
    public void revoke(String runnerId) {
        RunnerConnection c = connections.remove(runnerId);
        hosts.remove(runnerId);
        if (c != null) c.close("revoked");
    }

    /**
     * The review's re-read of a checkout on a runner.
     *
     * @throws IOException when the runner is not connected, or git there could not answer
     */
    public String patchSince(String runnerId, String directory, String base) throws IOException {
        RemoteRunHost host = hosts.get(runnerId);
        RunnerConnection c = connections.get(runnerId);
        if (host == null || c == null || !c.isOpen()) {
            String name = store.findById(runnerId).map(Runner::name).orElse(runnerId);
            throw new IOException("Runner '" + name + "' is offline; this is the change as last read.");
        }
        return host.patchSinceRemote(directory, base);
    }

    @Scheduled(fixedDelay = 15_000)
    public void sweep() {
        long now = System.currentTimeMillis();
        for (RunnerConnection c : List.copyOf(connections.values())) {
            if (!c.isOpen() || now - c.lastHeartbeat() > SILENCE_MS) {
                log.warn("Runner '{}' went silent; closing.", c.runnerName());
                if (connections.remove(c.runnerId(), c)) hosts.remove(c.runnerId());
                c.close("no heartbeat for " + (SILENCE_MS / 1000) + " s");
            }
        }
    }

    private void touch(String id, boolean force) {
        long now = System.currentTimeMillis();
        Long last = touched.get(id);
        if (!force && last != null && now - last < TOUCH_INTERVAL_MS) return;
        touched.put(id, now);
        try {
            store.touchLastSeen(id, now);
        } catch (RuntimeException e) {
            log.debug("Could not record last sight of runner {}: {}", id, e.getMessage());
        }
    }
}
