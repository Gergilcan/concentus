package com.concentus.runners;

import com.concentus.runners.protocol.Frame;

import java.util.concurrent.CompletableFuture;

/**
 * The hub's end of one runner's socket, as {@link RemoteRunHost} uses it.
 *
 * <p>An interface so the host can be tested against an in-memory link that answers what a runner
 * would, and so the socket's own concerns — sessions, partial messages, closing — stay in
 * {@link RunnerConnection}.
 */
public interface RunnerLink {

    String runnerId();

    String runnerName();

    /** What the runner said about itself; null until it has. */
    Frame.Hello hello();

    boolean isOpen();

    /**
     * Sends a request and completes with its ack — exceptionally with an {@link java.io.IOException}
     * when the socket closes first. {@code reqId} must be the frame's own.
     */
    CompletableFuture<Frame.Ack> request(String reqId, Frame frame);

    /** Fire and forget: stdin bytes, a stop. */
    void send(Frame frame);

    /** What a process's frames are delivered to. Detached by the link itself once the process exits. */
    interface ProcessListener {
        void line(String line);

        void log(String text);

        void exit(int code);
    }

    void attachProcess(String procId, ProcessListener listener);

    void detachProcess(String procId);

    /** How many processes are attached — what "busy" means on the roster. */
    int busy();
}
