package com.rummiclub.app;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;

public class ServerWarmupTest {
    private static final class Clock implements ServerWarmup.Time {
        long now;
        public long now() { return now; }
        public void sleep(long millis) { now += millis; }
    }
    @Test public void waitsThenCachesReadinessAndInvalidatesOnFailure() throws Exception {
        Clock clock = new Clock(); int[] calls = {0};
        ServerWarmup warmup = new ServerWarmup(clock, (url, timeout) -> ++calls[0] >= 3);
        warmup.await("https://game.onrender.com");
        assertEquals(6000, clock.now); assertEquals(3, calls[0]);
        warmup.await("https://game.onrender.com/"); assertEquals(3, calls[0]);
        warmup.failed("https://game.onrender.com");
        warmup.await("https://game.onrender.com"); assertEquals(4, calls[0]);
        clock.now += 600_001;
        warmup.await("https://game.onrender.com"); assertEquals(5, calls[0]);
    }
    @Test public void stopsAtDeadlineAndDoesNotProbeOtherHosts() throws Exception {
        Clock clock = new Clock(); int[] calls = {0};
        ServerWarmup warmup = new ServerWarmup(clock, (url, timeout) -> { calls[0]++; throw new IOException(); });
        warmup.await("https://onrender.com.evil.example"); assertEquals(0, calls[0]);
        try { warmup.await("https://game.onrender.com"); fail(); } catch (IOException expected) { }
        assertEquals(120_000, clock.now); assertEquals(40, calls[0]);
    }
    @Test public void cancellationDoesNotProbeOrSendAnything() throws Exception {
        ServerWarmup warmup = new ServerWarmup(new Clock(), (url, timeout) -> { fail(); return true; });
        Thread.currentThread().interrupt();
        try { warmup.await("https://game.onrender.com"); fail(); }
        catch (InterruptedException expected) { }
        finally { Thread.interrupted(); }
    }
}
