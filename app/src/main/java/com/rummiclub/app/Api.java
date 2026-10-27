package com.rummiclub.app;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

final class Api {
    static final class Failure extends IOException {
        final int status;
        Failure(String message, int status) { super(message); this.status = status; }
    }
    static JsonObject request(String base, String token, String route, JsonObject body) throws Exception {
        URI uri = URI.create(base);
        if (uri.getHost() == null || uri.getUserInfo() != null || (!"https".equals(uri.getScheme()) && !(BuildConfig.DEBUG && "http".equals(uri.getScheme()))))
            throw new IOException(BuildConfig.DEBUG ? "Укажите адрес http:// или https://" : "Нужен HTTPS-адрес сервера");
        HttpURLConnection connection = (HttpURLConnection) new URL(base.replaceAll("/+$", "") + "/api/" + route).openConnection();
        connection.setRequestMethod("POST"); connection.setConnectTimeout(7000); connection.setReadTimeout(10000);
        connection.setInstanceFollowRedirects(false); connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        if (!token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
        try {
            try (OutputStream out = connection.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream == null) throw new IOException("Сервер не ответил");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = stream) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = in.read(buffer)) != -1) { bytes.write(buffer, 0, count); if (bytes.size() > 262144) throw new IOException("Слишком большой ответ сервера"); }
            }
            JsonObject result;
            try { result = JsonParser.parseString(bytes.toString("UTF-8")).getAsJsonObject(); }
            catch (RuntimeException invalid) { throw new IOException("По этому адресу нет сервера Rummi Club"); }
            if (status < 200 || status >= 300) throw new Failure(result.has("error") ? result.get("error").getAsString() : "Ошибка сети " + status, status);
            return result;
        } finally { connection.disconnect(); }
    }
}
