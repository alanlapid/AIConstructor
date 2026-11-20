package io.github.alanlapid.aiconstructor.ai;

import com.google.gson.JsonObject;

import java.time.Duration;
import java.util.Map;

/** Values from the "provider" section of config.yml shared by every connector. */
public record ProviderSettings(
        String apiKey,
        String baseUrl,
        Duration timeout,
        double temperature,
        int maxOutputTokens,
        JsonObject planSchema,
        String openAiResponseFormat,
        Map<String, String> extraHeaders,
        boolean anthropicRefusalFallback,
        String ollamaThink) {

    /** base-url from the config, or the given default when it is empty. Never ends with a slash. */
    public String baseUrlOr(String defaultUrl) {
        String url = baseUrl == null || baseUrl.isBlank() ? defaultUrl : baseUrl.trim();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public boolean hasKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
