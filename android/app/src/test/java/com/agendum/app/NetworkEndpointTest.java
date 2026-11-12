package com.agendum.app;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;

public class NetworkEndpointTest {
    @Test public void acceptsHttpsAndRemovesTrailingSlash() {
        assertEquals("https://sync.example.com", NetworkEndpoint.normalize(" https://sync.example.com/ "));
    }
    @Test public void rejectsCleartextAndAmbiguousEndpoints() {
        for (String base : new String[]{"http://sync.example.com", "http://192.168.1.2:8766", "https://user@host", "https://host?token=x", "https://host/#x", "https://host/path", "https://host:70000"})
            assertEquals(base, "", NetworkEndpoint.normalize(base));
    }
    @Test public void oldHttpCredentialsFailBeforeOpeningConnection() throws Exception {
        try {
            new SyncTransport().call("http://127.0.0.1:1", "/api/v1/mutations", null, "must-not-be-sent");
            fail("明文连接必须在打开套接字前拒绝");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("HTTPS")); }
    }
}
