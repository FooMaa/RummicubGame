package com.rummiclub.app;

import com.google.gson.*;
import java.io.*;
import java.net.*;

final class Api {
    private static final ServerWarmup WARMUP = ServerWarmup.network();
    static final class Failure extends IOException {
        final int status;
        Failure(String message, int status) { super(message); this.status = status; }
    }
    static JsonObject request(String base, String token, String route, JsonObject body) throws Exception {
        URI uri = URI.create(base);
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null || (!"https".equals(uri.getScheme()) && !(BuildConfig.DEBUG && "http".equals(uri.getScheme()))))
            throw new IOException(BuildConfig.DEBUG ? "Укажите адрес http:// или https://" : "Нужен HTTPS-адрес сервера");
        WARMUP.await(base);
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        try {
            HttpTransport.Reply response = HttpTransport.post(base.replaceAll("/+$", "") + "/api/" + route, token, body.toString());
            JsonObject result;
            try { result = JsonParser.parseString(response.body()).getAsJsonObject(); }
            catch (RuntimeException invalid) { throw new IOException("По этому адресу нет сервера Rummi Club"); }
            if (response.status() < 200 || response.status() >= 300)
                throw new Failure(result.has("error") ? result.get("error").getAsString() : "Ошибка сети " + response.status(), response.status());
            WARMUP.succeeded(base);
            return result;
        } catch (IOException error) { WARMUP.failed(base); throw error; }
    }
}
