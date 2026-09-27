package io.github.alanlapid.aiconstructor.ai;

/**
 * One LLM backend. Implementations are called from an async thread and may block.
 */
public interface AIProvider extends AutoCloseable {

    /**
     * Asks the model for a build plan.
     *
     * @param model        model id to use for this attempt
     * @param systemPrompt builder instructions
     * @param prompt       the player's request
     * @param image        optional reference photo (null when none)
     * @return the raw text answer, expected to contain the JSON plan
     */
    String generate(String model, String systemPrompt, String prompt, ImageData image) throws ProviderException;

    /** Human readable name shown in /aibuild status. */
    String name();

    @Override
    default void close() {
    }
}
