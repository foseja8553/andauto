package com.torquescope;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** One request in flight; a timeout closes the stream to unblock a stalled read. */
final class ElmTransport {
    private final InputStream in;
    private final OutputStream out;
    private final ScheduledExecutorService deadlines;
    private final Runnable abort;

    ElmTransport(InputStream in, OutputStream out, ScheduledExecutorService deadlines, Runnable abort) {
        this.in = new BufferedInputStream(in, 512);
        this.out = out;
        this.deadlines = deadlines;
        this.abort = abort;
    }

    String command(String cmd, int timeoutMs) throws IOException {
        Object gate = new Object();
        boolean[] done = {false};
        ScheduledFuture<?> timeout = deadlines.schedule(() -> {
            synchronized (gate) { if (!done[0]) abort.run(); }
        }, timeoutMs, TimeUnit.MILLISECONDS);
        try {
            out.write((cmd + "\r").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            StringBuilder response = new StringBuilder(64);
            for (int i = 0; i < 4096; i++) {
                int c = in.read();
                if (c < 0) throw new EOFException();
                if (c == '>') return response.toString();
                response.append((char)c);
            }
            throw new IOException("Oversized response");
        } finally {
            synchronized (gate) { done[0] = true; }
            timeout.cancel(false);
        }
    }
}
