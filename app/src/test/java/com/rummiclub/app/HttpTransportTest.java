package com.rummiclub.app;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class HttpTransportTest {
    interface Handler { void respond(Socket socket) throws Exception; }
    static final class Peer implements AutoCloseable {
        final ServerSocket listener = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        final AtomicInteger requests = new AtomicInteger();
        final CountDownLatch received = new CountDownLatch(1), release = new CountDownLatch(1);
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        volatile String request;
        Peer(Handler handler) throws IOException {
            worker.submit(() -> {
                while (!listener.isClosed()) {
                    try (Socket socket = listener.accept()) {
                        socket.setSoTimeout(3000);
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); int b;
                        while ((b = socket.getInputStream().read()) >= 0) {
                            bytes.write(b); if (bytes.toString("UTF-8").endsWith("\r\n\r\n")) break;
                        }
                        request = bytes.toString("UTF-8"); requests.incrementAndGet(); received.countDown();
                        for (String line : request.split("\r\n")) if (line.toLowerCase().startsWith("content-length:"))
                            socket.getInputStream().readNBytes(Integer.parseInt(line.substring(15).trim()));
                        handler.respond(socket);
                    } catch (Exception ignored) { if (listener.isClosed()) return; }
                }
            });
        }
        String url() { return "http://127.0.0.1:" + listener.getLocalPort(); }
        public void close() throws Exception { release.countDown(); listener.close(); worker.shutdownNow(); worker.awaitTermination(2, TimeUnit.SECONDS); }
    }
    static void respond(Socket socket, int status, String body, String headers) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        socket.getOutputStream().write(("HTTP/1.1 " + status + " Result\r\nConnection: close\r\nContent-Length: " + bytes.length + "\r\n" + headers + "\r\n").getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().write(bytes);
    }
    @Test public void readinessDoesNotTransmitAuthorizationAndRejectsOversizedBody() throws Exception {
        try (Peer peer = new Peer(socket -> respond(socket, 200, "x".repeat(4097), ""))) {
            try { HttpTransport.get(peer.url()+"/health", 3000); fail(); } catch (HttpTransport.Failure expected) { }
            assertFalse(peer.request.toLowerCase().contains("authorization"));
            assertTrue(peer.request.startsWith("GET /health "));
        }
    }
    @Test public void neverReplaysMutationWhenServerDisconnectsAfterReceivingIt() throws Exception {
        try (Peer peer = new Peer(socket -> { })) {
            try { HttpTransport.post(peer.url()+"/api/room/draw", "test-session", "{}"); fail(); }
            catch (HttpTransport.Failure expected) { assertEquals("Запрос игры", expected.phase); }
            assertEquals(1, peer.requests.get());
            assertTrue(peer.request.toLowerCase().contains("authorization: bearer test-session"));
        }
    }
    @Test public void redirectsDoNotForwardCredentialsOrReplayPost() throws Exception {
        try (Peer peer = new Peer(socket -> respond(socket, 307, "{}", "Location: /other\r\n"))) {
            assertEquals(307, HttpTransport.post(peer.url()+"/api/room/play", "test-session", "{}").status());
            assertEquals(1, peer.requests.get());
        }
    }
    @Test public void interruptCancelsPendingRequestPromptly() throws Exception {
        CountDownLatch hold = new CountDownLatch(1);
        try (Peer peer = new Peer(socket -> hold.await(5, TimeUnit.SECONDS))) {
            Thread caller = new Thread(() -> {
                try { HttpTransport.get(peer.url()+"/health", 5000); fail(); }
                catch (IOException expected) { }
            });
            caller.start(); assertTrue(peer.received.await(3, TimeUnit.SECONDS));
            caller.interrupt(); caller.join(1000); assertFalse(caller.isAlive());
        } finally { hold.countDown(); }
    }
}
