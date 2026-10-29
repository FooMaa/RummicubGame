package com.rummiclub.app;

import com.google.gson.JsonParser;
import java.io.*;
import java.net.*;
import java.util.*;

/** Wake sleeping free Render instances before sending a mutation. Never replays a mutation. */
final class ServerWarmup {
    static final class NotReady extends IOException {
        NotReady(IOException cause) { super("Сервер не подтвердил готовность", cause); }
    }
    interface Time { long now(); void sleep(long millis) throws InterruptedException; }
    interface Probe { boolean ready(String base, int timeout) throws IOException; }
    private final Time time;
    private final Probe probe;
    private final Map<String, Long> readyUntil = new HashMap<>();
    ServerWarmup(Time time, Probe probe) { this.time = time; this.probe = probe; }
    static ServerWarmup network() {
        return new ServerWarmup(new Time() {
            public long now() { return System.nanoTime() / 1_000_000; }
            public void sleep(long millis) throws InterruptedException { Thread.sleep(millis); }
        }, ServerWarmup::health);
    }
    static boolean isRender(String base) {
        try { String host = URI.create(base).getHost(); return host != null && host.endsWith(".onrender.com"); }
        catch (IllegalArgumentException e) { return false; }
    }
    void await(String base) throws IOException, InterruptedException {
        if (!isRender(base)) return;
        base = base.replaceAll("/+$", "");
        synchronized (readyUntil) { Long until = readyUntil.get(base); if (until != null && until > time.now()) return; }
        long deadline = time.now() + 120_000;
        IOException lastError = null;
        while (time.now() < deadline) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            try {
                if (probe.ready(base, (int) Math.min(5000, Math.max(1, (deadline - time.now()) / 2)))) {
                    succeeded(base); return;
                }
                lastError = null;
            } catch (IOException error) { lastError = error; /* readiness GET is safe to repeat */ }
            long remaining = deadline - time.now();
            if (remaining > 0) time.sleep(Math.min(3000, remaining));
        }
        throw new NotReady(lastError);
    }
    void succeeded(String base) { synchronized (readyUntil) { readyUntil.put(base.replaceAll("/+$", ""), time.now() + 600_000); } }
    void failed(String base) { synchronized (readyUntil) { readyUntil.remove(base.replaceAll("/+$", "")); } }
    private static boolean health(String base, int timeout) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + "/health").openConnection();
        c.setUseCaches(false);
        c.setConnectTimeout(timeout); c.setReadTimeout(timeout); c.setInstanceFollowRedirects(false);
        try {
            if (c.getResponseCode() != 200) return false;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024]; int n;
                while ((n = in.read(buffer)) != -1) { bytes.write(buffer, 0, n); if (bytes.size() > 4096) return false; }
                try { return "ok".equals(JsonParser.parseString(bytes.toString("UTF-8")).getAsJsonObject().get("status").getAsString()); }
                catch (RuntimeException invalid) { return false; }
            }
        } finally { c.disconnect(); }
    }
}
