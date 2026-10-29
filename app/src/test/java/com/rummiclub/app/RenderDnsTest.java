package com.rummiclub.app;

import org.junit.*;
import static org.junit.Assert.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.*;

public class RenderDnsTest {
    @Test public void systemFailureUsesFallbackForRenderAndCachesResult() throws Exception {
        AtomicInteger fallbackCalls = new AtomicInteger();
        List<InetAddress> addresses = List.of(InetAddress.getByAddress(new byte[]{127,0,0,1}));
        RenderDns dns = new RenderDns(host -> { throw new UnknownHostException(); }, host -> { fallbackCalls.incrementAndGet(); return addresses; });
        assertEquals(addresses, dns.lookup("rummiclub.onrender.com"));
        assertEquals(addresses, dns.lookup("rummiclub.onrender.com"));
        assertEquals(1, fallbackCalls.get());
    }
    @Test public void privateAndOtherHostnamesNeverGoToPublicFallback() throws Exception {
        RenderDns dns = new RenderDns(host -> { throw new UnknownHostException(); }, host -> { fail("Private hostname leaked"); return List.of(); });
        for (String hostname : List.of("server.lan", "onrender.com.attacker.example", "localhost")) {
            try { dns.lookup(hostname); fail(); } catch (UnknownHostException expected) { }
        }
    }
    @Test public void workingSystemDnsDoesNotContactFallback() throws Exception {
        List<InetAddress> addresses = List.of(InetAddress.getByAddress(new byte[]{127,0,0,1}));
        RenderDns dns = new RenderDns(host -> addresses, host -> { fail(); return List.of(); });
        assertEquals(addresses, dns.lookup("rummiclub.onrender.com"));
    }
    @Test public void liveFallbackCanResolveAndReachRenderWithoutSystemDns() throws Exception {
        Assume.assumeTrue("1".equals(System.getenv("RUMMI_LIVE_NETWORK")));
        RenderDns forced = new RenderDns(host -> { throw new UnknownHostException(); }, resolverFallback -> {
            // Deliberately fail system DNS while using the production HTTPS fallback.
            return RenderDns.httpsLookup(resolverFallback);
        });
        OkHttpClient client = new OkHttpClient.Builder().dns(forced).readTimeout(90, java.util.concurrent.TimeUnit.SECONDS).callTimeout(120, java.util.concurrent.TimeUnit.SECONDS).build();
        try (Response response = client.newCall(new Request.Builder().url("https://rummiclub.onrender.com/health").build()).execute()) {
            assertEquals(200, response.code()); assertTrue(response.body().string().contains("ok"));
        } finally { client.connectionPool().evictAll(); }
    }
}
