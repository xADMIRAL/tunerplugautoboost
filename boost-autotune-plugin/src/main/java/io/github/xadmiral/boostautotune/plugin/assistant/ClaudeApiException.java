package io.github.xadmiral.boostautotune.plugin.assistant;

import java.io.IOException;
import java.util.Map;

/** A non-2xx answer from the Messages API, with the status and the API's error text. */
public final class ClaudeApiException extends IOException {
    private final int status;
    private final String body;

    public ClaudeApiException(int status, String body) {
        super(describe(status, body));
        this.status = status;
        this.body = body == null ? "" : body;
    }

    public int status() {
        return status;
    }

    public String body() {
        return body;
    }

    /** True for statuses worth retrying (rate limit, overload, server error). */
    public boolean retryable() {
        return status == 408 || status == 409 || status == 429 || status >= 500;
    }

    private static String describe(int status, String body) {
        String detail = "";
        try {
            Map<String, Object> m = Json.obj(Json.parse(body));
            Map<String, Object> err = m == null ? null : Json.obj(m.get("error"));
            if (err != null) {
                String type = Json.str(err, "type");
                String msg = Json.str(err, "message");
                detail = (type == null ? "" : type + ": ") + (msg == null ? "" : msg);
            }
        } catch (RuntimeException e) {
            detail = body == null ? "" : body.length() > 300 ? body.substring(0, 300) : body;
        }
        String head;
        switch (status) {
            case 401:
                head = "API key rejected";
                break;
            case 403:
                head = "Forbidden";
                break;
            case 404:
                head = "Not found (check the base URL and the model name)";
                break;
            case 429:
                head = "Rate limit";
                break;
            case 529:
                head = "API overloaded";
                break;
            default:
                head = "HTTP " + status;
        }
        return head + (detail.isEmpty() ? "" : " - " + detail);
    }
}
