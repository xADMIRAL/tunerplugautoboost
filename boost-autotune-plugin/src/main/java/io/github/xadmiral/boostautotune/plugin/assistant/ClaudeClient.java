package io.github.xadmiral.boostautotune.plugin.assistant;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Claude Messages API ({@code POST /v1/messages}) over a {@link ClaudeTransport}: builds the
 * request from plain maps, sets the headers, parses the answer. Retries a rate limit or an
 * overload a couple of times with a pause; everything else is reported as it is.
 */
public final class ClaudeClient {
    public static final String API_VERSION = "2023-06-01";
    /** Server-side refusal fallbacks: routed by refusal category, no model list to maintain. */
    public static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private final ClaudeTransport transport;
    private final String baseUrl;
    private final String apiKey;
    private int maxRetries = 2;
    private long retryPauseMs = 3000;

    public ClaudeClient(ClaudeTransport transport, String baseUrl, String apiKey) {
        this.transport = transport;
        String b = baseUrl == null ? "" : baseUrl.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        this.baseUrl = b.isEmpty() ? AssistantConfig.DEFAULT_BASE_URL : b;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public void setRetries(int maxRetries, long pauseMs) {
        this.maxRetries = maxRetries;
        this.retryPauseMs = pauseMs;
    }

    public String messagesUrl() {
        return baseUrl + "/v1/messages";
    }

    /**
     * Builds the request body for one turn.
     * @param effort {@code low..max} or empty for the API default
     * @param fallbacks add {@code fallbacks: "default"} (needs the beta header, see {@link #headers(boolean)})
     */
    public static Map<String, Object> request(String model, int maxTokens, String system, List<Object> tools,
                                              List<Object> messages, String effort, boolean fallbacks) {
        Map<String, Object> body = Json.object();
        body.put("model", model);
        body.put("max_tokens", Long.valueOf(maxTokens));
        if (system != null && !system.isEmpty()) {
            Map<String, Object> block = Json.object();
            block.put("type", "text");
            block.put("text", system);
            Map<String, Object> cache = Json.object();
            cache.put("type", "ephemeral");
            block.put("cache_control", cache);
            List<Object> sys = new java.util.ArrayList<Object>();
            sys.add(block);
            body.put("system", sys);
        }
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
        }
        body.put("messages", messages);
        if (effort != null && !effort.trim().isEmpty()) {
            Map<String, Object> out = Json.object();
            out.put("effort", effort.trim());
            body.put("output_config", out);
        }
        if (fallbacks) {
            body.put("fallbacks", "default");
        }
        return body;
    }

    public Map<String, String> headers(boolean fallbacks) {
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("content-type", "application/json");
        h.put("accept", "application/json");
        h.put("x-api-key", apiKey);
        h.put("anthropic-version", API_VERSION);
        if (fallbacks) {
            h.put("anthropic-beta", FALLBACK_BETA);
        }
        return h;
    }

    /** Sends one request; the parsed response object (content, stop_reason, usage, ...). */
    public Map<String, Object> send(Map<String, Object> body, boolean fallbacks) throws IOException {
        if (apiKey.isEmpty()) {
            throw new IOException("No API key: paste one on the Assistant tab");
        }
        String json = Json.write(body);
        int attempt = 0;
        while (true) {
            try {
                String text = transport.post(messagesUrl(), headers(fallbacks), json);
                Map<String, Object> m;
                try {
                    m = Json.obj(Json.parse(text));
                } catch (IllegalArgumentException e) {
                    throw new IOException("The API answered with something that is not JSON: "
                            + (text.length() > 200 ? text.substring(0, 200) + "..." : text));
                }
                if (m == null) {
                    throw new IOException("Unexpected API answer: " + text);
                }
                if ("error".equals(Json.str(m, "type"))) {
                    Map<String, Object> err = Json.obj(m.get("error"));
                    throw new IOException("API error: " + (err == null ? text : Json.str(err, "type") + ": " + Json.str(err, "message")));
                }
                return m;
            } catch (ClaudeApiException e) {
                if (!e.retryable() || attempt >= maxRetries) {
                    throw e;
                }
                attempt++;
                try {
                    Thread.sleep(retryPauseMs * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }
}
