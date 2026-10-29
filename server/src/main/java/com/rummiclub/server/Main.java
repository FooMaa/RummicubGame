package com.rummiclub.server;

import java.nio.file.Path;

public final class Main {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        String connection = System.getenv("YDB_CONNECTION_STRING");
        YdbSnapshots snapshots = connection == null ? null : new YdbSnapshots(connection);
        if (args.length == 1 && args[0].equals("--init-ydb")) {
            if (snapshots == null) throw new IllegalArgumentException("YDB_CONNECTION_STRING required");
            try (snapshots) { snapshots.initialize(); }
            return;
        }
        Path database = snapshots == null ? Path.of(System.getenv().getOrDefault("DATABASE_PATH", "data/rummiclub.db"))
            : java.nio.file.Files.createTempFile("rummi-cache-", ".db");
        Server server = new Server(port, database, snapshots);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { try { server.close(); } catch (Exception ignored) { } }));
        server.start();
        System.out.println("Rummi Club server listening on port " + server.port());
    }
}
