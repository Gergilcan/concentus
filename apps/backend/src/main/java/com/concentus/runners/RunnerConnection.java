package com.concentus.runners;

import com.concentus.runners.protocol.Frame;
import com.concentus.runners.protocol.Frames;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One connected runner's socket, on the hub.
 *
 * <p>Correlates acks with the requests that asked for them, routes a process's frames to whoever
 * is reading that process, and on close fails everything still outstanding: a request that would
 * otherwise wait forever, and a process whose exit will never arrive — reported as exit −1, which
 * ends the turn the way a killed child would.
 */
public class RunnerConnection implements RunnerLink {

    private static final Logger log = LoggerFactory.getLogger(RunnerConnection.class);

    private final Runner runner;
    private final WebSocketSession session;
    private final ObjectMapper mapper;
    private final Map<String, CompletableFuture<Frame.Ack>> pending = new ConcurrentHashMap<>();
    private final Map<String, ProcessListener> processes = new ConcurrentHashMap<>();
    private volatile Frame.Hello hello;
    private volatile long lastHeartbeat = System.currentTimeMillis();
    private volatile int reportedBusy;
    private final long connectedAt = System.currentTimeMillis();
    private volatile boolean closed;

    public RunnerConnection(Runner runner, WebSocketSession session, ObjectMapper mapper) {
        this.runner = runner;
        this.session = session;
        this.mapper = mapper;
    }

    public Runner runner() {
        return runner;
    }

    @Override
    public String runnerId() {
        return runner.id();
    }

    @Override
    public String runnerName() {
        return hello != null && hello.name() != null && !hello.name().isBlank() ? hello.name() : runner.name();
    }

    @Override
    public Frame.Hello hello() {
        return hello;
    }

    void welcomed(Frame.Hello hello) {
        this.hello = hello;
        this.lastHeartbeat = System.currentTimeMillis();
    }

    public long lastHeartbeat() {
        return lastHeartbeat;
    }

    public long connectedAt() {
        return connectedAt;
    }

    /** What the runner last said it was running — for the roster; the attached count is what the hub knows. */
    public int reportedBusy() {
        return Math.max(reportedBusy, busy());
    }

    @Override
    public boolean isOpen() {
        return !closed && session.isOpen();
    }

    @Override
    public CompletableFuture<Frame.Ack> request(String reqId, Frame frame) {
        CompletableFuture<Frame.Ack> future = new CompletableFuture<>();
        if (closed) {
            future.completeExceptionally(new IOException("Runner '" + runnerName() + "' is disconnected."));
            return future;
        }
        pending.put(reqId, future);
        try {
            send(frame);
        } catch (RuntimeException e) {
            pending.remove(reqId);
            future.completeExceptionally(new IOException("Could not reach runner '" + runnerName() + "': "
                    + e.getMessage(), e));
        }
        return future;
    }

    @Override
    public void send(Frame frame) {
        try {
            session.sendMessage(new TextMessage(Frames.write(mapper, frame)));
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @Override
    public void attachProcess(String procId, ProcessListener listener) {
        processes.put(procId, listener);
    }

    @Override
    public void detachProcess(String procId) {
        processes.remove(procId);
    }

    @Override
    public int busy() {
        return processes.size();
    }

    /** A frame from the runner, after hello. */
    void deliver(Frame frame) {
        lastHeartbeat = System.currentTimeMillis();
        switch (frame) {
            case Frame.Heartbeat h -> reportedBusy = h.busy();
            case Frame.Ack ack -> {
                CompletableFuture<Frame.Ack> f = pending.remove(ack.reqId());
                if (f != null) f.complete(ack);
                else log.debug("Runner {} acked unknown request {}", runner.id(), ack.reqId());
            }
            case Frame.Stdout out -> {
                ProcessListener l = processes.get(out.procId());
                if (l != null) l.line(out.line());
            }
            case Frame.Log line -> {
                ProcessListener l = line.procId() == null ? null : processes.get(line.procId());
                if (l != null) l.log(line.text());
                else log.info("Runner {}: {}", runnerName(), line.text());
            }
            case Frame.Exit exit -> {
                ProcessListener l = processes.remove(exit.procId());
                if (l != null) l.exit(exit.code());
            }
            default -> log.debug("Runner {} sent a frame the hub does not take: {}", runner.id(),
                    frame.getClass().getSimpleName());
        }
    }

    /** Ends everything: outstanding requests fail, running processes are reported gone, the socket closes. */
    void close(String reason) {
        if (closed) return;
        closed = true;
        IOException gone = new IOException("Runner '" + runnerName() + "' disconnected (" + reason + ").");
        List<CompletableFuture<Frame.Ack>> waiting = new ArrayList<>(pending.values());
        pending.clear();
        waiting.forEach(f -> f.completeExceptionally(gone));
        List<ProcessListener> running = new ArrayList<>(processes.values());
        processes.clear();
        for (ProcessListener l : running) {
            try {
                l.log("Runner '" + runnerName() + "' disconnected (" + reason + "); the process's output is lost.");
                l.exit(-1);
            } catch (RuntimeException ignored) {
                // a listener that is already gone must not keep the others from hearing
            }
        }
        try {
            if (session.isOpen()) session.close(CloseStatus.NORMAL.withReason(reason));
        } catch (IOException ignored) {
            // it was going away anyway
        }
    }
}
