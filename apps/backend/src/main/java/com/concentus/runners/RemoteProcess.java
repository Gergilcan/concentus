package com.concentus.runners;

import com.concentus.runners.protocol.Frame;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

/**
 * A process on a runner, wearing {@link Process} so the executors need not know.
 *
 * <p>They read lines from {@link #getInputStream()}, write the prompt to {@link #getOutputStream()},
 * keep the process on the run and {@link #destroy()} it on Stop. All of that works here: stdout is
 * fed by the runner's frames, stdin is forwarded to it, destroy asks it to kill, and exit arrives
 * as a frame — or as −1 when the socket closes first.
 */
public final class RemoteProcess extends Process implements RunnerLink.ProcessListener {

    private static final byte[] EOF = new byte[0];

    private final RunnerLink link;
    private final String procId;
    /** Rewrites mirror paths in what goes to stdin — the merge prompt names the workers' folders. */
    private final UnaryOperator<String> rewrite;
    private final LineStream out = new LineStream();
    private final Stdin in = new Stdin();
    private final CountDownLatch done = new CountDownLatch(1);
    private volatile Integer exit;

    public RemoteProcess(RunnerLink link, String procId, UnaryOperator<String> rewrite) {
        this.link = link;
        this.procId = procId;
        this.rewrite = rewrite == null ? UnaryOperator.identity() : rewrite;
    }

    public String procId() {
        return procId;
    }

    // ----------------------------------------------------------------- what the runner sends

    @Override
    public void line(String line) {
        out.offer(line);
    }

    /**
     * Onto stdout as well: the stream handler emits any line that is not stream-json as a system
     * event, which is exactly where a "waiting for a slot" belongs. No second channel to plumb.
     */
    @Override
    public void log(String text) {
        out.offer(text);
    }

    @Override
    public void exit(int code) {
        if (exit != null) return;
        exit = code;
        out.end();
        done.countDown();
        link.detachProcess(procId);
    }

    // ----------------------------------------------------------------- Process

    @Override
    public OutputStream getOutputStream() {
        return in;
    }

    @Override
    public InputStream getInputStream() {
        return out;
    }

    @Override
    public InputStream getErrorStream() {
        return InputStream.nullInputStream();
    }

    @Override
    public int waitFor() throws InterruptedException {
        done.await();
        return exit;
    }

    @Override
    public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
        return done.await(timeout, unit);
    }

    @Override
    public int exitValue() {
        Integer e = exit;
        if (e == null) throw new IllegalThreadStateException("process has not exited");
        return e;
    }

    @Override
    public boolean isAlive() {
        return exit == null;
    }

    @Override
    public void destroy() {
        if (exit != null) return;
        try {
            link.send(new Frame.ProcStop(procId));
        } catch (RuntimeException ignored) {
            // The link is gone, and with it the process's exit — close() on the link reports it.
        }
    }

    @Override
    public Process destroyForcibly() {
        destroy();
        return this;
    }

    // ----------------------------------------------------------------- streams

    /** Lines from the runner, read as bytes by whoever wraps this in a reader. */
    private static final class LineStream extends InputStream {
        private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
        private byte[] current = EOF;
        private int pos;
        private boolean ended;

        void offer(String line) {
            queue.add((line + "\n").getBytes(StandardCharsets.UTF_8));
        }

        void end() {
            queue.add(EOF);
        }

        private boolean fill() throws IOException {
            if (ended) return false;
            while (pos >= current.length) {
                try {
                    current = queue.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted");
                }
                pos = 0;
                if (current == EOF) {
                    ended = true;
                    return false;
                }
            }
            return true;
        }

        @Override
        public int read() throws IOException {
            if (!fill()) return -1;
            return current[pos++] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (!fill()) return -1;
            int n = Math.min(len, current.length - pos);
            System.arraycopy(current, pos, b, off, n);
            pos += n;
            return n;
        }
    }

    /** The process's stdin: buffered here, sent on flush, ended on close. */
    private final class Stdin extends OutputStream {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private boolean closed;

        @Override
        public synchronized void write(int b) {
            buffer.write(b);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            buffer.write(b, off, len);
        }

        @Override
        public synchronized void flush() {
            if (buffer.size() == 0) return;
            String text = rewrite.apply(buffer.toString(StandardCharsets.UTF_8));
            buffer.reset();
            link.send(new Frame.ProcStdin(procId,
                    Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)), false));
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            flush();
            try {
                link.send(new Frame.ProcStdin(procId, null, true));
            } catch (RuntimeException ignored) {
                // the link is gone; nothing is reading that stdin any more
            }
        }
    }
}
