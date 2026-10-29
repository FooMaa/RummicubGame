package com.rummiclub.app;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
import java.net.*;
import javax.net.ssl.SSLHandshakeException;

public class ConnectionErrorsTest {
    @Test public void preservesTransportCauseAfterWarmupDeadline() {
        assertTrue(ConnectionErrors.describe(new ServerWarmup.NotReady(new UnknownHostException())).contains("DNS"));
        assertTrue(ConnectionErrors.describe(new ServerWarmup.NotReady(new SocketTimeoutException())).contains("TIMEOUT"));
        assertTrue(ConnectionErrors.describe(new IOException(new SSLHandshakeException("private details")) ).contains("TLS"));
        assertFalse(ConnectionErrors.describe(new IOException(new SSLHandshakeException("private details"))).contains("private details"));
        assertTrue(ConnectionErrors.describe(new ConnectException()).contains("CONNECT"));
        assertTrue(ConnectionErrors.describe(new ServerWarmup.NotReady(null)).contains("две минуты"));
    }
}
