package io.github.xadmiral.boostautotune.plugin.assistant;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The JDK transport against a local HTTP server: headers, body, status handling. */
class HttpTransportTest {
    @Test
    void postsHeadersAndBodyAndMapsErrors() throws Exception {
        final List<String> seenBodies = new ArrayList<String>();
        final List<String> seenKeys = new ArrayList<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", new HttpHandler() {
            public void handle(HttpExchange ex) throws IOException {
                InputStream in = ex.getRequestBody();
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[1024];
                int n;
                while ((n = in.read(chunk)) > 0) {
                    buf.write(chunk, 0, n);
                }
                String body = buf.toString("UTF-8");
                seenBodies.add(body);
                seenKeys.add(ex.getRequestHeaders().getFirst("x-api-key"));
                byte[] out;
                int status;
                if (body.contains("\"boom\"")) {
                    status = 429;
                    out = "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"too fast\"}}".getBytes("UTF-8");
                } else {
                    status = 200;
                    out = "{\"ok\":true,\"echo\":\"Ж\"}".getBytes("UTF-8");
                }
                ex.getResponseHeaders().add("content-type", "application/json");
                ex.sendResponseHeaders(status, out.length);
                OutputStream os = ex.getResponseBody();
                os.write(out);
                os.close();
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            HttpTransport t = new HttpTransport(5000, 5000);
            Map<String, String> headers = new LinkedHashMap<String, String>();
            headers.put("content-type", "application/json");
            headers.put("x-api-key", "sk-local");
            String reply = t.post(base + "/v1/messages", headers, "{\"hello\":\"мир\"}");
            assertEquals("{\"ok\":true,\"echo\":\"Ж\"}", reply);
            assertEquals("{\"hello\":\"мир\"}", seenBodies.get(0));
            assertEquals("sk-local", seenKeys.get(0));
            try {
                t.post(base + "/v1/messages", headers, "{\"boom\":1}");
                fail("expected ClaudeApiException");
            } catch (ClaudeApiException e) {
                assertEquals(429, e.status());
                assertTrue(e.retryable());
                assertTrue(e.getMessage().contains("Rate limit"), e.getMessage());
                assertTrue(e.getMessage().contains("too fast"), e.getMessage());
            }
            // the client on top of it: base url normalisation and the error surfacing
            ClaudeClient c = new ClaudeClient(t, base + "/", "sk-local");
            c.setRetries(0, 1);
            Map<String, Object> resp = c.send(ClaudeClient.request("claude-opus-5", 50, "sys", null, new ArrayList<Object>(), "", false), false);
            assertEquals(Boolean.TRUE, resp.get("ok"));
            assertEquals(base + "/v1/messages", c.messagesUrl());
        } finally {
            server.stop(0);
        }
    }
}
