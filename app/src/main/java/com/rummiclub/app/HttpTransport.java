package com.rummiclub.app;

import java.io.*;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import okio.BufferedSink;

/** Shares TLS connections between readiness and API without replaying game commands. */
final class HttpTransport {
    static final class Failure extends IOException {
        final String phase;
        Failure(String phase, IOException cause) { super(phase, cause); this.phase = phase; }
    }
    record Reply(int status, String body) { }
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().dns(RenderDns.network())
        .followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS).build();

    static Reply get(String url, int timeout) throws IOException {
        OkHttpClient client = CLIENT.newBuilder().retryOnConnectionFailure(true).callTimeout(timeout, TimeUnit.MILLISECONDS).build();
        return execute(client, new Request.Builder().url(url).header("Cache-Control", "no-cache").get().build(), 4096, "Проверка готовности /health");
    }
    static Reply post(String url, String token, String json) throws IOException {
        byte[] bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        RequestBody body = new RequestBody() {
            @Override public MediaType contentType() { return MediaType.get("application/json; charset=utf-8"); }
            @Override public long contentLength() { return bytes.length; }
            @Override public boolean isOneShot() { return true; }
            @Override public void writeTo(BufferedSink sink) throws IOException { sink.write(bytes); }
        };
        Request.Builder request = new Request.Builder().url(url).post(body);
        if (!token.isEmpty()) request.header("Authorization", "Bearer " + token);
        return execute(CLIENT, request.build(), 262144, "Запрос игры");
    }
    private static Reply execute(OkHttpClient client, Request request, int limit, String phase) throws IOException {
        // Awaiting an async call lets Future.cancel(true) immediately close the network call too.
        Call call = client.newCall(request);
        java.util.concurrent.CompletableFuture<Reply> result = new java.util.concurrent.CompletableFuture<>();
        call.enqueue(new Callback() {
            public void onFailure(Call ignored, IOException error) { result.completeExceptionally(error); }
            public void onResponse(Call ignored, Response response) {
                try (response) {
                    if (response.body() == null) throw new IOException("Пустой ответ");
                    try (InputStream in = response.body().byteStream()) {
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int n;
                        while ((n = in.read(buffer)) != -1) {
                            bytes.write(buffer, 0, n);
                            if (bytes.size() > limit) throw new IOException("Слишком большой ответ");
                        }
                        result.complete(new Reply(response.code(), bytes.toString("UTF-8")));
                    }
                } catch (Exception error) { result.completeExceptionally(error); }
            }
        });
        try { return result.get(Math.max(1, client.callTimeoutMillis()), TimeUnit.MILLISECONDS); }
        catch (java.util.concurrent.TimeoutException error) {
            result.cancel(false); call.cancel(); throw new Failure(phase, new java.net.SocketTimeoutException("Call deadline reached"));
        }
        catch (InterruptedException error) {
            result.cancel(false); call.cancel(); Thread.currentThread().interrupt();
            throw new Failure(phase, new InterruptedIOException("Подключение отменено"));
        } catch (java.util.concurrent.ExecutionException error) {
            throw new Failure(phase, error.getCause() instanceof IOException io ? io : new IOException(error.getCause()));
        }
    }
}
