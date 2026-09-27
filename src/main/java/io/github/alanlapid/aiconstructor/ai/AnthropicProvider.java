package io.github.alanlapid.aiconstructor.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicRetryableException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.NotFoundException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.errors.UnprocessableEntityException;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Claude through the official Anthropic Java SDK, with structured JSON output. */
public final class AnthropicProvider implements AIProvider {

    private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private final ProviderSettings settings;
    private final AnthropicClient client;
    private final JsonOutputFormat.Schema schema;

    public AnthropicProvider(ProviderSettings settings) {
        this.settings = settings;
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
                .apiKey(settings.apiKey())
                .timeout(settings.timeout())
                // Retries and model rotation are handled by PlanGenerator.
                .maxRetries(0);
        if (settings.baseUrl() != null && !settings.baseUrl().isBlank()) {
            builder.baseUrl(settings.baseUrlOr(""));
        }
        this.client = builder.build();

        Map<String, Object> raw = new Gson().fromJson(settings.planSchema(), new TypeToken<Map<String, Object>>() {
        }.getType());
        JsonOutputFormat.Schema.Builder schemaBuilder = JsonOutputFormat.Schema.builder();
        raw.forEach((k, v) -> schemaBuilder.putAdditionalProperty(k, JsonValue.from(v)));
        this.schema = schemaBuilder.build();
    }

    @Override
    public String name() {
        return "Anthropic";
    }

    @Override
    public String generate(String model, String systemPrompt, String prompt, ImageData image) throws ProviderException {
        List<ContentBlockParam> blocks = new ArrayList<>();
        if (image != null) {
            blocks.add(ContentBlockParam.ofImage(ImageBlockParam.builder()
                    .source(Base64ImageSource.builder()
                            .data(image.base64())
                            .mediaType(Base64ImageSource.MediaType.of(image.mimeType()))
                            .build())
                    .build()));
        }
        blocks.add(ContentBlockParam.ofText(TextBlockParam.builder().text(prompt).build()));

        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(settings.maxOutputTokens())
                .system(systemPrompt)
                .outputConfig(OutputConfig.builder()
                        .format(JsonOutputFormat.builder().schema(schema).build())
                        .build())
                .addUserMessageOfBlockParams(blocks);
        if (settings.anthropicRefusalFallback() && supportsServerFallback(model)) {
            params.putAdditionalHeader("anthropic-beta", FALLBACK_BETA);
            params.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }

        Message response;
        try {
            response = client.messages().create(params.build());
        } catch (UnauthorizedException | PermissionDeniedException e) {
            throw new ProviderException(ProviderException.Kind.FATAL, ProviderException.Reason.OTHER,
                    "The Anthropic API key is invalid or not allowed: " + e.getMessage());
        } catch (BadRequestException | UnprocessableEntityException e) {
            throw new ProviderException(ProviderException.Kind.FATAL, ProviderException.Reason.OTHER,
                    "Request rejected by Anthropic: " + e.getMessage());
        } catch (NotFoundException e) {
            throw new ProviderException(ProviderException.Kind.SKIP_MODEL, ProviderException.Reason.UNAVAILABLE,
                    "Model not found: " + model);
        } catch (RateLimitException e) {
            throw new ProviderException(ProviderException.Kind.RETRY, ProviderException.Reason.QUOTA, "Rate limited: " + e.getMessage());
        } catch (InternalServerException e) {
            throw new ProviderException(ProviderException.Kind.RETRY, ProviderException.Reason.OVERLOADED,
                    "Anthropic overloaded/unavailable (HTTP " + e.statusCode() + ")");
        } catch (AnthropicServiceException e) {
            throw ProviderException.fromStatus(e.statusCode(), e.getMessage());
        } catch (AnthropicIoException | AnthropicRetryableException e) {
            throw new ProviderException(ProviderException.Kind.RETRY, ProviderException.Reason.NETWORK, "Network error: " + e.getMessage());
        }

        StopReason stop = response.stopReason().orElse(null);
        if (StopReason.REFUSAL.equals(stop)) {
            throw new ProviderException(ProviderException.Kind.FATAL, ProviderException.Reason.OTHER,
                    "The model declined this request. Try describing it differently.");
        }
        if (StopReason.MAX_TOKENS.equals(stop)) {
            throw new ProviderException(ProviderException.Kind.RETRY, ProviderException.Reason.INVALID,
                    "The plan was too long and got cut off (raise max-output-tokens)");
        }
        return response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(text -> text.text())
                .collect(Collectors.joining());
    }

    /** Server-side refusal fallbacks are available for Claude Opus 5 and Claude Fable 5.1. */
    private static boolean supportsServerFallback(String model) {
        return model.startsWith("claude-opus-5") || model.startsWith("claude-fable-5-1");
    }

    @Override
    public void close() {
        client.close();
    }
}
