package io.github.alanlapid.aiconstructor.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Locale;
import java.util.Map;

/** Google Gemini (generativelanguage.googleapis.com), using structured JSON output. */
public final class GeminiProvider implements AIProvider {

    private final ProviderSettings settings;
    private final JsonObject geminiSchema;

    public GeminiProvider(ProviderSettings settings) {
        this.settings = settings;
        this.geminiSchema = toGeminiSchema(settings.planSchema()).getAsJsonObject();
    }

    @Override
    public String name() {
        return "Gemini";
    }

    @Override
    public String generate(String model, String systemPrompt, String prompt, ImageData image) throws ProviderException {
        JsonObject body = new JsonObject();

        JsonObject system = new JsonObject();
        system.add("parts", textParts(systemPrompt));
        body.add("systemInstruction", system);

        JsonArray parts = new JsonArray();
        JsonObject text = new JsonObject();
        text.addProperty("text", prompt);
        parts.add(text);
        if (image != null) {
            JsonObject inline = new JsonObject();
            inline.addProperty("mime_type", image.mimeType());
            inline.addProperty("data", image.base64());
            JsonObject imagePart = new JsonObject();
            imagePart.add("inline_data", inline);
            parts.add(imagePart);
        }
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.add("parts", parts);
        JsonArray contents = new JsonArray();
        contents.add(user);
        body.add("contents", contents);

        JsonObject gen = new JsonObject();
        gen.addProperty("responseMimeType", "application/json");
        gen.add("responseSchema", geminiSchema);
        gen.addProperty("temperature", settings.temperature());
        gen.addProperty("maxOutputTokens", settings.maxOutputTokens());
        body.add("generationConfig", gen);

        String url = settings.baseUrlOr("https://generativelanguage.googleapis.com/v1beta") + "/models/" + model + ":generateContent";
        JsonObject root = HttpJson.post(url, Map.of("x-goog-api-key", settings.apiKey()), body, settings.timeout());

        JsonArray candidates = root.getAsJsonArray("candidates");
        if (candidates == null || candidates.isEmpty()) {
            throw new ProviderException(ProviderException.Kind.RETRY, ProviderException.Reason.INVALID,
                    "Empty answer (possibly blocked by safety filters)");
        }
        JsonObject candidate = candidates.get(0).getAsJsonObject();
        String finish = candidate.has("finishReason") ? candidate.get("finishReason").getAsString() : "";
        if ("MAX_TOKENS".equals(finish)) {
            throw new ProviderException(ProviderException.Kind.RETRY, ProviderException.Reason.INVALID,
                    "The plan was too long and got cut off (raise max-output-tokens)");
        }
        StringBuilder out = new StringBuilder();
        JsonObject content = candidate.getAsJsonObject("content");
        if (content != null && content.has("parts")) {
            for (JsonElement p : content.getAsJsonArray("parts")) {
                JsonObject part = p.getAsJsonObject();
                boolean thought = part.has("thought") && part.get("thought").getAsBoolean();
                if (!thought && part.has("text")) {
                    out.append(part.get("text").getAsString());
                }
            }
        }
        return out.toString();
    }

    private static JsonArray textParts(String value) {
        JsonObject part = new JsonObject();
        part.addProperty("text", value);
        JsonArray arr = new JsonArray();
        arr.add(part);
        return arr;
    }

    /** Converts standard JSON Schema to Gemini's OpenAPI subset: upper-case types, no additionalProperties. */
    static JsonElement toGeminiSchema(JsonElement element) {
        if (element.isJsonObject()) {
            JsonObject out = new JsonObject();
            for (Map.Entry<String, JsonElement> e : element.getAsJsonObject().entrySet()) {
                if (e.getKey().equals("additionalProperties")) {
                    continue;
                }
                if (e.getKey().equals("type") && e.getValue().isJsonPrimitive()) {
                    out.addProperty("type", e.getValue().getAsString().toUpperCase(Locale.ROOT));
                } else if (e.getKey().equals("properties")) {
                    JsonObject props = new JsonObject();
                    for (Map.Entry<String, JsonElement> prop : e.getValue().getAsJsonObject().entrySet()) {
                        props.add(prop.getKey(), toGeminiSchema(prop.getValue()));
                    }
                    out.add("properties", props);
                } else {
                    out.add(e.getKey(), toGeminiSchema(e.getValue()));
                }
            }
            return out;
        }
        if (element.isJsonArray()) {
            JsonArray out = new JsonArray();
            element.getAsJsonArray().forEach(item -> out.add(toGeminiSchema(item)));
            return out;
        }
        return element;
    }
}
