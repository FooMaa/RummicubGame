package com.rummiclub.app;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import okhttp3.*;

/** Uses public DoH only when system DNS fails for a public Render hostname. */
final class RenderDns implements Dns {
    private final Dns system, fallback;
    private final Map<String, Entry> cache = new HashMap<>();
    private record Entry(List<InetAddress> addresses, long expires) { }
    RenderDns(Dns system, Dns fallback) { this.system = system; this.fallback = fallback; }
    @Override public List<InetAddress> lookup(String hostname) throws UnknownHostException {
        try { List<InetAddress> addresses = system.lookup(hostname); if (!addresses.isEmpty()) return addresses; }
        catch (UnknownHostException failure) { if (!hostname.toLowerCase(Locale.ROOT).endsWith(".onrender.com")) throw failure; }
        if (!hostname.toLowerCase(Locale.ROOT).endsWith(".onrender.com")) throw new UnknownHostException(hostname);
        synchronized (cache) {
            Entry entry = cache.get(hostname);
            if (entry != null && entry.expires > System.nanoTime()) return entry.addresses;
        }
        List<InetAddress> addresses = List.copyOf(fallback.lookup(hostname));
        if (addresses.isEmpty()) throw new UnknownHostException(hostname);
        synchronized (cache) { cache.put(hostname, new Entry(addresses, System.nanoTime() + TimeUnit.SECONDS.toNanos(30))); }
        return addresses;
    }
    static RenderDns network() {
        return new RenderDns(Dns.SYSTEM, RenderDns::httpsLookup);
    }
    static List<InetAddress> httpsLookup(String hostname) throws UnknownHostException {
        try {
            // Bootstrap only the DNS resolver. The game's IP is always resolved dynamically.
            OkHttpClient bootstrap = new OkHttpClient.Builder().dns(name -> {
                if (!name.equals("dns.google")) throw new UnknownHostException(name);
                return List.of(InetAddress.getByAddress(new byte[]{8,8,8,8}), InetAddress.getByAddress(new byte[]{8,8,4,4}));
            }).callTimeout(10, TimeUnit.SECONDS).connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(4, TimeUnit.SECONDS).followRedirects(false).build();
            HttpUrl url = HttpUrl.get("https://dns.google/resolve").newBuilder().addQueryParameter("name", hostname).addQueryParameter("type", "A").build();
            try (Response response = bootstrap.newCall(new Request.Builder().url(url).get().build()).execute()) {
                if (!response.isSuccessful() || response.body() == null) throw new IOException("DNS resolver unavailable");
                String body = response.peekBody(65537).string();
                if (body.length() > 65536) throw new IOException("DNS response too large");
                JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                if (json.get("Status").getAsInt() != 0 || !json.has("Answer")) throw new UnknownHostException(hostname);
                List<InetAddress> addresses = new ArrayList<>();
                for (JsonElement answer : json.getAsJsonArray("Answer")) {
                    JsonObject record = answer.getAsJsonObject(); if (record.get("type").getAsInt() != 1) continue;
                    String[] pieces = record.get("data").getAsString().split("\\.");
                    if (pieces.length != 4) continue;
                    byte[] bytes = new byte[4];
                    for (int i=0;i<4;i++) { int part=Integer.parseInt(pieces[i]);if(part<0||part>255)throw new IOException("Invalid DNS address");bytes[i]=(byte)part; }
                    addresses.add(InetAddress.getByAddress(hostname, bytes));
                }
                if (addresses.isEmpty()) throw new UnknownHostException(hostname);
                return addresses;
            } finally { bootstrap.connectionPool().evictAll(); }
        } catch (Exception error) {
            UnknownHostException failure = new UnknownHostException("DNS resolution failed");failure.initCause(error);throw failure;
        }
    }
}
