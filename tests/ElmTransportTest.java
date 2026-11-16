package com.torquescope;

import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ElmTransportTest {
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            AtomicInteger aborts = new AtomicInteger();
            ElmTransport t = new ElmTransport(new ByteArrayInputStream("414980>410D64>".getBytes("US-ASCII")), out, timers, aborts::incrementAndGet);
            check(t.command("0149",100).equals("414980"));
            check(t.command("010D",100).equals("410D64"));
            check(out.toString("US-ASCII").equals("0149\r010D\r"));
            Thread.sleep(150);
            check(aborts.get()==0);
            try { t.command("010D",100); throw new AssertionError("Expected EOF"); } catch(EOFException expected) {}
            BlockingStream blocked = new BlockingStream();
            t = new ElmTransport(blocked, out, timers, blocked::close);
            long start = System.nanoTime();
            try { t.command("0149",50); throw new AssertionError("Expected timeout close"); } catch(IOException expected) {}
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1500);
            t = new ElmTransport(new ByteArrayInputStream(new byte[4096]), out, timers, ()->{});
            try {t.command("010D",100); throw new AssertionError("Expected response limit");} catch(IOException expected) {}
            System.out.println("PASS: framing, sequential responses, cancelled deadlines, EOF, stalled read, size limit");
        } finally {timers.shutdownNow();}
    }
    static class BlockingStream extends InputStream {
        boolean closed;
        public synchronized int read() throws IOException {
            while(!closed) {try {wait();} catch(InterruptedException e) {Thread.currentThread().interrupt(); throw new IOException(e);}}
            throw new IOException("closed");
        }
        public synchronized void close() {closed=true; notifyAll();}
    }
}
