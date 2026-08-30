package com.concentus.runners.agent;

import com.concentus.runners.protocol.Frame;
import com.concentus.runners.protocol.Frames;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The runner's end of the protocol: one outbound socket to the hub, kept open.
 *
 * <p>Connects, says hello, answers requests, streams process output, heart-beats every fifteen
 * seconds, and reconnects with backoff when the socket drops. The one thing it does not retry is
 * a refused handshake: {@code 401} means the token resolves to nothing and {@code 403} that it was
 * revoked, and a runner hammering a hub with a dead token helps nobody — it stops and says why.
 *
 * <p>Plain Java on purpose — no Spring, no database, no web server — so {@code java -jar … runner}
 * is a few hundred milliseconds to a connection, and so the same class runs inside a full backend
 * (the desktop's "also a runner" mode) with that backend's own CLI, folders and ceiling.
 */
public final class RunnerAgent {

    private static final Logger log = LoggerFactory.getLogger(RunnerAgent.class);

    public static final Duration HEARTBEAT = Duration.ofSeconds(15);
    static final long BACKOFF_MIN_MS = 2_000;
    static final long BACKOFF_MAX_MS = 60_000;

    /**
     * How the agent is started.
     *
     * @param hubUrl  {@code http(s)://host[:port]} or {@code ws(s)://…}; the socket path is added here
     * @param name    what to be called, or null to keep the registered name
     * @param version what to report as this runner's version
     */
    public record Config(String hubUrl, String token, String name, String version) {
        public String httpUrl() {
            return normalize(hubUrl, false);
        }

        public String socketUrl() {
            return normalize(hubUrl, true) + "/ws/runner";
        }

        private static String normalize(String raw, boolean socket) {
            String s = raw == null ? "" : raw.trim();
            while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
            String lower = s.toLowerCase(Locale.ROOT);
            if (lower.startsWith("ws://")) s = "http://" + s.substring(5);
            else if (lower.startsWith("wss://")) s = "https://" + s.substring(6);
            else if (!lower.startsWith("http://") && !lower.startsWith("https://")) s = "https://" + s;
            // A pasted URL may carry the socket path already; the http form never does.
            if (s.toLowerCase(Locale.ROOT).endsWith("/ws/runner")) s = s.substring(0, s.length() - "/ws/runner".length());
            if (!socket) return s;
            return s.startsWith("https://") ? "wss://" + s.substring(8) : "ws://" + s.substring(7);
        }
    }

    /** What the tray and {@code /api/runners/self} show. */
    public record Status(boolean configured, boolean connected, String hubUrl, String name, String error) {
    }

    private final Config config;
    private final AgentRuntime runtime;
    private final ObjectMapper mapper;
    private final HttpClient http;
    /** Requests are answered off the socket's own thread: a clone or a slot wait must not stall the frames behind it. */
    private final ExecutorService workers = Executors.newCachedThreadPool(daemon("runner-work"));
    /** One thread sends, so frames leave in the order they were queued and no two sends overlap. */
    private final ExecutorService sender = Executors.newSingleThreadExecutor(daemon("runner-send"));
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(daemon("runner-timer"));
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final AtomicInteger backoff = new AtomicInteger();
    private volatile WebSocket socket;
    private volatile boolean connected;
    private volatile boolean closing;
    private volatile boolean fatal;
    private volatile String lastError;
    private volatile String welcomedAs;
    private volatile ScheduledFuture<?> heartbeat;
    private volatile CompletableFuture<Void> welcome = new CompletableFuture<>();

    public RunnerAgent(Config config, AgentRuntime runtime) {
        this(config, runtime, new ObjectMapper(), HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build());
    }

    RunnerAgent(Config config, AgentRuntime runtime, ObjectMapper mapper, HttpClient http) {
        this.config = config;
        this.runtime = runtime;
        this.mapper = mapper;
        this.http = http;
    }

    public Config config() {
        return config;
    }

    public Status status() {
        return new Status(true, connected, config.httpUrl(), welcomedAs != null ? welcomedAs : config.name(), lastError);
    }

    public boolean isConnected() {
        return connected;
    }

    /** Whether the hub refused the token — the one failure that stops the agent for good. */
    public boolean isFatal() {
        return fatal;
    }

    /** Starts connecting; returns at once. */
    public void start() {
        timer.execute(this::connect);
    }

    /** Completes when the hub has welcomed this connection; fails when the token was refused. */
    public CompletableFuture<Void> welcomed() {
        return welcome;
    }

    /** Blocks until {@link #stop()} or a refused token. */
    public void awaitStop() throws InterruptedException {
        stopped.await();
    }

    public void stop() {
        closing = true;
        WebSocket s = socket;
        if (s != null) {
            try {
                s.sendClose(WebSocket.NORMAL_CLOSURE, "stopping").exceptionally(t -> null);
            } catch (RuntimeException ignored) {
                // already gone
            }
        }
        runtime.killAll();
        timer.shutdownNow();
        sender.shutdownNow();
        workers.shutdownNow();
        stopped.countDown();
    }

    // ------------------------------------------------------------------ the socket

    private void connect() {
        if (closing) return;
        URI uri = URI.create(config.socketUrl());
        log.info("Connecting to {}…", uri);
        http.newWebSocketBuilder()
                .header("Authorization", "Bearer " + config.token())
                .connectTimeout(Duration.ofSeconds(20))
                .buildAsync(uri, new Listener())
                .whenComplete((ws, error) -> {
                    if (error != null) {
                        onConnectFailed(error);
                        return;
                    }
                    socket = ws;
                    send(hello());
                });
    }

    private void onConnectFailed(Throwable error) {
        Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                ? error.getCause() : error;
        if (cause instanceof WebSocketHandshakeException h && h.getResponse() != null) {
            int status = h.getResponse().statusCode();
            if (status == 401 || status == 403) {
                lastError = status == 401 ? "The hub does not know this token." : "This runner's token was revoked.";
                log.error("{} Not retrying.", lastError);
                fatal = true;
                welcome.completeExceptionally(new IOException(lastError));
                stopped.countDown();
                return;
            }
            lastError = "The hub answered " + status + " to the handshake.";
        } else {
            lastError = "Could not connect: " + (cause.getMessage() == null ? cause.toString() : cause.getMessage());
        }
        log.warn("{}", lastError);
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (closing) return;
        int n = backoff.getAndIncrement();
        long delay = Math.min(BACKOFF_MAX_MS, BACKOFF_MIN_MS << Math.min(n, 5));
        log.info("Reconnecting in {} s.", delay / 1000);
        timer.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
    }

    private void onClosed(String why) {
        boolean was = connected;
        connected = false;
        socket = null;
        ScheduledFuture<?> h = heartbeat;
        if (h != null) h.cancel(false);
        if (was) log.warn("Disconnected from the hub ({}).", why);
        // Processes keep running to completion on this side; their output has nowhere to go and
        // the hub has already told their runs. A turn is not resumable across a disconnection.
        if (!closing) {
            welcome = new CompletableFuture<>();
            scheduleReconnect();
        }
    }

    private Frame.Hello hello() {
        String hostname;
        try {
            hostname = java.net.InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            hostname = null;
        }
        String cmd = runtime.support() == null ? null : runtime.support().command().orElse(null);
        boolean loggedIn = runtime.support() != null && runtime.support().available();
        return new Frame.Hello(config.version(), System.getProperty("os.name"), System.getProperty("os.arch"),
                hostname, System.getProperty("java.version"), cmd, loggedIn, runtime.claudeVersion(), authKind(loggedIn),
                capacity(), java.io.File.separator, runtime.runsRoot().toString(), config.httpUrl(),
                runtime.contextRoots(), config.name());
    }

    /** How this runner's CLI authenticates — reported, so the roster does not have to guess. */
    static String authKind(boolean loggedIn) {
        if (notBlank(System.getenv("ANTHROPIC_API_KEY"))) return "api-key";
        if (notBlank(System.getenv("CLAUDE_CODE_OAUTH_TOKEN")) || loggedIn) return "subscription";
        return "none";
    }

    private int capacity() {
        try {
            return runtimeCapacity();
        } catch (RuntimeException e) {
            return 1;
        }
    }

    private volatile int declaredCapacity = -1;

    public void declareCapacity(int n) {
        declaredCapacity = n;
    }

    private int runtimeCapacity() {
        return declaredCapacity > 0 ? declaredCapacity : 4;
    }

    // ------------------------------------------------------------------ frames

    private void send(Frame frame) {
        WebSocket s = socket;
        if (s == null) return;
        String text = Frames.write(mapper, frame);
        try {
            sender.execute(() -> {
                try {
                    s.sendText(text, true).join();
                } catch (RuntimeException e) {
                    log.debug("send failed: {}", e.getMessage());
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // stopping
        }
    }

    private void ack(String reqId, Object result) {
        send(new Frame.Ack(reqId, true, null, Frames.result(mapper, result)));
    }

    private void refuse(String reqId, Exception e) {
        String message = e.getMessage() == null ? e.toString() : e.getMessage();
        send(new Frame.Ack(reqId, false, message, null));
    }

    private void dispatch(Frame frame) {
        switch (frame) {
            case Frame.Welcome w -> {
                connected = true;
                backoff.set(0);
                welcomedAs = w.name();
                lastError = null;
                log.info("Connected to {} as '{}' ({}).", config.httpUrl(), w.name(), w.runnerId());
                heartbeat = timer.scheduleAtFixedRate(() -> send(new Frame.Heartbeat(runtime.busy())),
                        HEARTBEAT.toMillis(), HEARTBEAT.toMillis(), TimeUnit.MILLISECONDS);
                welcome.complete(null);
            }
            case Frame.WorkspaceSync r -> answer(r.reqId(), () -> runtime.sync(r));
            case Frame.GitClone r -> answer(r.reqId(), () -> runtime.clone(r));
            case Frame.GitHead r -> answer(r.reqId(), () -> runtime.head(r));
            case Frame.GitPatchOf r -> answer(r.reqId(), () -> runtime.patchOf(r));
            case Frame.GitPatchSince r -> answer(r.reqId(), () -> runtime.patchSince(r));
            case Frame.ContextResolve r -> answer(r.reqId(), () -> runtime.resolve(r));
            case Frame.FsRead r -> answer(r.reqId(), () -> runtime.read(r));
            case Frame.WorkspaceDelete r -> answer(r.reqId(), () -> {
                runtime.delete(r);
                return null;
            });
            case Frame.ProcStart r -> workers.execute(() -> startProcess(r));
            case Frame.ProcStdin r -> runtime.stdin(r);
            case Frame.ProcStop r -> runtime.stop(r.procId());
            default -> log.debug("The hub sent a frame this runner does not take: {}", frame.getClass().getSimpleName());
        }
    }

    private interface Answer {
        Object get() throws Exception;
    }

    private void answer(String reqId, Answer work) {
        workers.execute(() -> {
            try {
                ack(reqId, work.get());
            } catch (Exception e) {
                log.debug("Request {} failed: {}", reqId, e.getMessage());
                refuse(reqId, e);
            }
        });
    }

    private void startProcess(Frame.ProcStart r) {
        try {
            runtime.start(r,
                    line -> send(new Frame.Stdout(r.procId(), line)),
                    waiting -> send(new Frame.Log(r.procId(), waiting.replace("execution.max-processes",
                            "the runner's EXECUTION_MAX_PROCESSES"))),
                    code -> send(new Frame.Exit(r.procId(), code)));
            ack(r.reqId(), null);
        } catch (IOException | RuntimeException e) {
            refuse(r.reqId(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            refuse(r.reqId(), new IOException("interrupted"));
        }
    }

    /** Text frames arrive in parts; a message is dispatched once its last part is here. */
    private final class Listener implements WebSocket.Listener {
        private final StringBuilder text = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                String whole = text.toString();
                text.setLength(0);
                try {
                    Frame frame = Frames.read(mapper, whole);
                    dispatch(frame);
                } catch (IllegalArgumentException e) {
                    log.warn("The hub sent something that is not a frame: {}", e.getMessage());
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            onClosed(statusCode + (reason == null || reason.isBlank() ? "" : " " + reason));
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            lastError = error.getMessage() == null ? error.toString() : error.getMessage();
            onClosed(lastError);
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static java.util.concurrent.ThreadFactory daemon(String prefix) {
        AtomicInteger n = new AtomicInteger(1);
        return r -> {
            Thread t = new Thread(r, prefix + "-" + n.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
    }

    /** The parts of an agent a caller assembles from a data directory and a CLI. */
    public static AgentRuntime runtime(Path dataDir, String claudeCommand, String contextRoots, int maxProcesses) {
        com.concentus.support.LocalClaudeSupport support = new com.concentus.support.LocalClaudeSupport(claudeCommand);
        com.concentus.service.ContextFolderResolver folders = new com.concentus.service.ContextFolderResolver(contextRoots);
        com.concentus.git.GitWorkspace git = new com.concentus.git.GitWorkspace(
                com.concentus.git.RepoExpander.standalone(), true, 300, 0);
        com.concentus.service.ProcessCeiling ceiling = new com.concentus.service.ProcessCeiling(
                com.concentus.config.Settings.of(java.util.Map.of("execution.max-processes",
                        String.valueOf(Math.max(1, maxProcesses)))));
        return new AgentRuntime(dataDir, support, folders, git, ceiling);
    }

    /** For a hello that reports the roots the runtime actually has. */
    static List<String> rootsOf(AgentRuntime runtime) {
        return runtime.contextRoots();
    }
}
