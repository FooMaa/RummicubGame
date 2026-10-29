package com.rummiclub.server;

import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Function;

public final class CloudFunction implements Function<Map<String,Object>, Map<String,Object>> {
    private static final Gson JSON = new Gson();
    private static Server server;
    private static synchronized Server server() throws Exception {
        if (server == null) {
            String connection = Objects.requireNonNull(System.getenv("YDB_CONNECTION_STRING"), "YDB connection required");
            server = new Server(-1, Files.createTempFile("rummi-cache-", ".db"), new YdbSnapshots(connection));
        }
        return server;
    }
    @Override public Map<String,Object> apply(Map<String,Object> event) {
        try {
            String body = Objects.toString(event.get("body"), "");
            if (body.length() > 90000) return response(413, Map.of("error", "Запрос слишком большой"));
            byte[] bytes = Boolean.TRUE.equals(event.get("isBase64Encoded")) ? Base64.getDecoder().decode(body) : body.getBytes(StandardCharsets.UTF_8);
            String authorization = null;
            if (event.get("headers") instanceof Map<?,?> headers)
                for (var entry : headers.entrySet()) if ("authorization".equalsIgnoreCase(entry.getKey().toString())) authorization = Objects.toString(entry.getValue(), "");
            String ip = "gateway";
            if (event.get("requestContext") instanceof Map<?,?> context && context.get("identity") instanceof Map<?,?> identity)
                ip = Objects.toString(identity.get("sourceIp"), "gateway");
            Server.Reply reply = server().invoke(Objects.toString(event.get("path"), "/"), Objects.toString(event.get("httpMethod"), "GET"), bytes, authorization, ip);
            return response(reply.status(), reply.body());
        } catch (IllegalArgumentException e) { return response(400, Map.of("error", "Некорректный запрос")); }
        catch (Exception e) {
            System.err.println("Function failed: " + e.getClass().getSimpleName());
            return response(503, Map.of("error", "Хранилище временно недоступно. Повторите запрос"));
        }
    }
    private static Map<String,Object> response(int status, Object body) {
        return Map.of("statusCode", status, "headers", Map.of("Content-Type", "application/json; charset=utf-8", "Cache-Control", "no-store"), "isBase64Encoded", false, "body", JSON.toJson(body));
    }
}
