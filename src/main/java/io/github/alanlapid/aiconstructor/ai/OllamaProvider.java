package io.github.alanlapid.aiconstructor.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

/** Ollama's native /api/chat endpoint, local or Ollama Cloud (https://ollama.com with an API key). */
public final class OllamaProvider implements AIProvider {

    private final ProviderSettings settings;

    public OllamaProvider(ProviderSettings settings) {
        this.settings = settings;
    }

    @Override
    public String name() {
        return "Ollama (" + settings.baseUrlOr("http://localhost:11434") + ")";
    }

    @Override
    public String generate(String model, String systemPrompt, String prompt, ImageData image) throws ProviderException {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("stream", false);
        body.add("format", settings.planSchema());
        // Reasoning models can spend the whole token budget thinking and never write the plan.
        String think = settings.ollamaThink() == null ? "" : settings.ollamaThink().trim().toLowerCase(java.util.Locale.ROOT);
        switch (think) {
            case "", "default" -> {
            }
            case "true", "false" -> body.addProperty("think", Boolean.parseBoolean(think));
            default -> body.addProperty("think", think);
        }

        JsonObject options = new JsonObject();
        options.addProperty("temperature", settings.temperature());
        options.addProperty("num_predict", settings.maxOutputTokens());
        body.add("options", options);

        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", systemPrompt);
        messages.add(system);
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", prompt);
        if (image != null) {
            JsonArray images = new JsonArray();
            images.add(image.base64());
            user.add("images", images);
        }
        messages.add(user);
        body.add("messages", messages);

        Map<String, String> headers = new LinkedHashMap<>(settings.extraHeaders());
        if (settings.hasKey()) {
            // Only needed when Ollama sits behind an authenticating proxy.
            headers.put("Authorization", "Bearer " + settings.apiKey());
        }
        JsonObject root = HttpJson.post(settings.baseUrlOr("http://localhost:11434") + "/api/chat", headers, body, settings.timeout());
        if (root.has("done_reason") && "length".equals(root.get("done_reason").getAsString())) {
            throw new ProviderException(ProviderException.Kind.RETRY, ProviderException.Reason.INVALID,
                    "The plan was too long and got cut off (raise max-output-tokens)");
        }
        JsonObject message = root.getAsJsonObject("message");
        if (message == null || !message.has("content")) {
            throw new ProviderException(ProviderException.Kind.RETRY, ProviderException.Reason.INVALID, "Answer without text");
        }
        return message.get("content").getAsString();
    }
}
