package io.github.alanlapid.aiconstructor.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.List;
import java.util.logging.Logger;

/**
 * Turns a prompt into a JSON build plan, rotating through the configured models when one is
 * overloaded or out of quota. Blocking: call from an async task.
 */
public final class PlanGenerator {

    public record Result(JsonObject plan, String model, long millis) {
    }

    /** Callbacks about each attempt, invoked from the async thread. */
    public interface Progress {
        void trying(String model, int attempt, int totalAttempts);

        void failed(String model, ProviderException.Reason reason);
    }

    public static final class GenerationException extends Exception {
        public GenerationException(String message) {
            super(message);
        }
    }

    private final AIProvider provider;
    private final List<String> models;
    private final int rounds;
    private final long retryDelayMs;
    private final String systemPrompt;
    private final Logger log;

    public PlanGenerator(AIProvider provider, List<String> models, int rounds, long retryDelayMs, String systemPrompt, Logger log) {
        this.provider = provider;
        this.models = List.copyOf(models);
        this.rounds = Math.max(1, rounds);
        this.retryDelayMs = Math.max(0, retryDelayMs);
        this.systemPrompt = systemPrompt;
        this.log = log;
    }

    public AIProvider provider() {
        return provider;
    }

    public List<String> models() {
        return models;
    }

    public Result generate(String prompt, ImageData image, Progress progress) throws GenerationException, InterruptedException {
        if (models.isEmpty()) {
            throw new GenerationException("No models configured (provider.models).");
        }
        String request = prompt == null || prompt.isBlank() ? "Build the structure shown in the photo." : prompt;
        int total = rounds * models.size();
        int attempt = 0;
        String lastError = "no answer";
        java.util.Set<String> skipped = new java.util.HashSet<>();
        for (int round = 0; round < rounds; round++) {
            for (String model : models) {
                attempt++;
                if (skipped.contains(model)) {
                    continue;
                }
                progress.trying(model, attempt, total);
                long start = System.currentTimeMillis();
                try {
                    String text = provider.generate(model, systemPrompt, request, image);
                    JsonObject plan = extractJson(text);
                    return new Result(plan, model, System.currentTimeMillis() - start);
                } catch (ProviderException e) {
                    lastError = model + ": " + e.getMessage();
                    log.info("Attempt " + attempt + "/" + total + " failed - " + lastError);
                    switch (e.kind()) {
                        case FATAL -> throw new GenerationException(e.getMessage());
                        case SKIP_MODEL -> skipped.add(model);
                        case RETRY -> Thread.sleep(retryDelayMs);
                    }
                    progress.failed(model, e.reason());
                }
                if (skipped.size() == models.size()) {
                    throw new GenerationException("None of the configured models is available. Last error: " + lastError);
                }
            }
        }
        throw new GenerationException("The AI did not answer after several attempts. Last error: " + lastError);
    }

    /**
     * Parses the plan. Models without structured output sometimes wrap JSON in markdown fences or add
     * text around it, so this falls back to the outermost {...}.
     */
    static JsonObject extractJson(String text) throws ProviderException {
        if (text == null || text.isBlank()) {
            throw new ProviderException(ProviderException.Kind.RETRY, ProviderException.Reason.INVALID, "Empty answer");
        }
        try {
            return JsonParser.parseString(text.trim()).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException ignored) {
            // fall through to the lenient path
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                return JsonParser.parseString(text.substring(start, end + 1)).getAsJsonObject();
            } catch (JsonParseException | IllegalStateException ignored) {
                // reported below
            }
        }
        throw new ProviderException(ProviderException.Kind.RETRY, ProviderException.Reason.INVALID, "The answer is not a valid JSON plan");
    }
}
