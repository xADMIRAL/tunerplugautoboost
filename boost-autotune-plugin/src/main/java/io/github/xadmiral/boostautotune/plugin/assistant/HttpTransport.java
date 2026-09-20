package io.github.xadmiral.boostautotune.plugin.assistant;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

/**
 * {@link ClaudeTransport} over {@link HttpURLConnection}: no third-party HTTP or TLS stack, so
 * the plugin cannot clash with the libraries TunerStudio loads, and the JVM's own proxy
 * settings apply. Answers may take minutes on a hard question: the read timeout is long.
 */
public final class HttpTransport implements ClaudeTransport {
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public HttpTransport() {
        this(30000, 10 * 60 * 1000);
    }

    public HttpTransport(int connectTimeoutMs, int readTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    @Override
    public String post(String url, Map<String, String> headers, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(connectTimeoutMs);
        c.setReadTimeout(readTimeoutMs);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setUseCaches(false);
        for (Map.Entry<String, String> h : headers.entrySet()) {
            c.setRequestProperty(h.getKey(), h.getValue());
        }
        byte[] bytes = body.getBytes("UTF-8");
        c.setFixedLengthStreamingMode(bytes.length);
        OutputStream out = c.getOutputStream();
        try {
            out.write(bytes);
        } finally {
            out.close();
        }
        int status = c.getResponseCode();
        InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = in == null ? "" : readAll(in);
        c.disconnect();
        if (status >= 400) {
            throw new ClaudeApiException(status, text);
        }
        return text;
    }

    private static String readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
            }
            return buf.toString("UTF-8");
        } finally {
            in.close();
        }
    }
}
