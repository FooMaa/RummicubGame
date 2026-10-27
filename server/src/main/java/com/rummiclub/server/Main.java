package com.rummiclub.server;

import java.nio.file.Path;

public final class Main {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        Server server = new Server(port, Path.of(System.getenv().getOrDefault("DATABASE_PATH", "data/rummiclub.db")));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { try { server.close(); } catch (Exception ignored) { } }));
        server.start();
        System.out.println("Rummi Club server listening on port " + server.port());
    }
}
