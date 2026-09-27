package io.github.alanlapid.aiconstructor.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** Small JSON-over-HTTP helper shared by the providers that have no official Java SDK. */
final class HttpJson {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private HttpJson() {
    }

    static JsonObject post(String url, Map<String, String> headers, JsonObject body, Duration timeout)
            throws ProviderException {
        HttpRequest.Builder request;
        try {
            request = HttpRequest.newBuilder(URI.create(url));
        } catch (IllegalArgumentException e) {
            throw new ProviderException(ProviderException.Kind.FATAL, ProviderException.Reason.OTHER, "Invalid base-url: " + url);
        }
        request.timeout(timeout).header("Content-Type", "application/json");
        headers.forEach(request::header);
        HttpResponse<String> response;
        try {
            response = HTTP.send(request.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ProviderException(ProviderException.Kind.RETRY, ProviderException.Reason.NETWORK,
                    "Network error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException(ProviderException.Kind.FATAL, ProviderException.Reason.OTHER, "Interrupted");
        }
        if (response.statusCode() != 200) {
            throw ProviderException.fromStatus(response.statusCode(), errorMessage(response.body()));
        }
        try {
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new ProviderException(ProviderException.Kind.RETRY, ProviderException.Reason.INVALID,
                    "Response is not JSON: " + e.getMessage());
        }
    }

    /** Extracts {"error":{"message":...}} (Google/OpenAI/Anthropic style) or {"error":"..."} (Ollama style). */
    static String errorMessage(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            String msg;
            if (root.has("error") && root.get("error").isJsonObject() && root.getAsJsonObject("error").has("message")) {
                msg = root.getAsJsonObject("error").get("message").getAsString();
            } else if (root.has("error") && root.get("error").isJsonPrimitive()) {
                msg = root.get("error").getAsString();
            } else {
                msg = body;
            }
            return msg.length() > 200 ? msg.substring(0, 200) + "..." : msg;
        } catch (RuntimeException e) {
            return body.length() > 150 ? body.substring(0, 150) : body;
        }
    }
}
