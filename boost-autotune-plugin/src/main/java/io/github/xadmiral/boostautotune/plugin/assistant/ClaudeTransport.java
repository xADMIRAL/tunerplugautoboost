package io.github.xadmiral.boostautotune.plugin.assistant;

import java.io.IOException;
import java.util.Map;

/** One HTTPS POST; implemented over the JDK for the plugin and by a stub in tests. */
public interface ClaudeTransport {
    /**
     * Posts a JSON body and returns the response body. A non-2xx status is reported as
     * {@link ClaudeApiException} so the caller can show the API's own error message.
     */
    String post(String url, Map<String, String> headers, String body) throws IOException;
}
