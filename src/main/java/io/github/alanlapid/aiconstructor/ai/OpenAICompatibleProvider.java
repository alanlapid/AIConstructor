package io.github.alanlapid.aiconstructor.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Any server speaking the OpenAI Chat Completions API: OpenAI, OpenRouter, Groq, DeepSeek, Mistral,
 * Together, LM Studio, vLLM, LocalAI...
 */
public final class OpenAICompatibleProvider implements AIProvider {

    private final ProviderSettings settings;

    public OpenAICompatibleProvider(ProviderSettings settings) {
        this.settings = settings;
    }

    @Override
    public String name() {
        return "OpenAI-compatible (" + settings.baseUrlOr("https://api.openai.com/v1") + ")";
    }

    @Override
    public String generate(String model, String systemPrompt, String prompt, ImageData image) throws ProviderException {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("temperature", settings.temperature());
        body.addProperty("max_tokens", settings.maxOutputTokens());

        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", systemPrompt);
        messages.add(system);

        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        if (image == null) {
            user.addProperty("content", prompt);
        } else {
            JsonArray content = new JsonArray();
            JsonObject text = new JsonObject();
            text.addProperty("type", "text");
            text.addProperty("text", prompt);
            content.add(text);
            JsonObject url = new JsonObject();
            url.addProperty("url", "data:" + image.mimeType() + ";base64," + image.base64());
            JsonObject imagePart = new JsonObject();
            imagePart.addProperty("type", "image_url");
            imagePart.add("image_url", url);
            content.add(imagePart);
            user.add("content", content);
        }
        messages.add(user);
        body.add("messages", messages);

        switch (settings.openAiResponseFormat().toLowerCase(Locale.ROOT)) {
            case "json_schema" -> {
                JsonObject schema = new JsonObject();
                schema.addProperty("name", "build_plan");
                schema.add("schema", settings.planSchema());
                JsonObject format = new JsonObject();
                format.addProperty("type", "json_schema");
                format.add("json_schema", schema);
                body.add("response_format", format);
            }
            case "json_object" -> {
                JsonObject format = new JsonObject();
                format.addProperty("type", "json_object");
                body.add("response_format", format);
            }
            default -> {
                // "none": rely on the system prompt; the plan is extracted from free text.
            }
        }

        Map<String, String> headers = new LinkedHashMap<>(settings.extraHeaders());
        if (settings.hasKey()) {
            headers.put("Authorization", "Bearer " + settings.apiKey());
        }
        String url = settings.baseUrlOr("https://api.openai.com/v1") + "/chat/completions";
        JsonObject root;
        try {
            root = HttpJson.post(url, headers, body, settings.timeout());
        } catch (ProviderException e) {
            if (e.kind() == ProviderException.Kind.FATAL && e.getMessage().contains("HTTP 400")
                    && !settings.openAiResponseFormat().equalsIgnoreCase("none")) {
                throw new ProviderException(ProviderException.Kind.FATAL, ProviderException.Reason.OTHER,
                        e.getMessage() + " (if this server does not support structured output, set provider.openai.response-format to json_object or none)");
            }
            throw e;
        }

        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new ProviderException(ProviderException.Kind.RETRY, ProviderException.Reason.INVALID, "Empty answer");
        }
        JsonObject choice = choices.get(0).getAsJsonObject();
        if (choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()
                && "length".equals(choice.get("finish_reason").getAsString())) {
            throw new ProviderException(ProviderException.Kind.RETRY, ProviderException.Reason.INVALID,
                    "The plan was too long and got cut off (raise max-output-tokens)");
        }
        JsonObject message = choice.getAsJsonObject("message");
        JsonElement content = message == null ? null : message.get("content");
        if (content == null || content.isJsonNull()) {
            if (message != null && message.has("refusal") && !message.get("refusal").isJsonNull()) {
                throw new ProviderException(ProviderException.Kind.FATAL, ProviderException.Reason.OTHER,
                        "The model refused: " + message.get("refusal").getAsString());
            }
            throw new ProviderException(ProviderException.Kind.RETRY, ProviderException.Reason.INVALID, "Answer without text");
        }
        return content.getAsString();
    }
}
