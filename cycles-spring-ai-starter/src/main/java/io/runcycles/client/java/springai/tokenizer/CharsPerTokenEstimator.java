package io.runcycles.client.java.springai.tokenizer;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Default {@link PromptTokenEstimator} implementation. Estimates tokens as
 * {@code totalPromptChars / charsPerToken}, where {@code charsPerToken} defaults to
 * 4 — a rough heuristic that approximates English text on OpenAI-family BPE
 * tokenizers (cl100k_base / o200k_base). Preserves the v0.2.0 behavior exactly.
 *
 * <p>Limitations of the heuristic, in case you need to know when to upgrade:
 * <ul>
 *   <li>Drifts on non-English text (CJK runs ~1 token per character; this heuristic
 *       under-estimates by 4x for those).</li>
 *   <li>Drifts on highly structured content (JSON / code) where tokenizer-specific
 *       merge rules matter.</li>
 *   <li>Doesn't account for special tokens injected by the provider (system prompt
 *       formatting, etc.) — those are typically a small constant overhead.</li>
 * </ul>
 *
 * <p>For tighter estimates, supply your own {@link PromptTokenEstimator} bean — for
 * example a wrapper around jtokkit (real BPE encoding for OpenAI models) or a
 * provider-specific tokenizer.
 *
 * <p>Multimodal messages (those whose {@code getText()} is null because the content
 * lives in {@code Media}) are skipped in the char count, matching v0.2.0 behavior.
 */
public class CharsPerTokenEstimator implements PromptTokenEstimator {

    /** Default chars-per-token ratio — heuristic that approximates OpenAI BPE on English. */
    public static final long DEFAULT_CHARS_PER_TOKEN = 4L;

    private final long charsPerToken;

    /**
     * Constructs an estimator with the default 4 chars-per-token ratio.
     */
    public CharsPerTokenEstimator() {
        this(DEFAULT_CHARS_PER_TOKEN);
    }

    /**
     * Constructs an estimator with an explicit chars-per-token ratio. Useful when the
     * default 4 under- or over-estimates for the language / content type your agent
     * actually processes.
     *
     * @param charsPerToken the divisor applied to total prompt characters to estimate
     *                      tokens. Must be positive.
     */
    public CharsPerTokenEstimator(long charsPerToken) {
        if (charsPerToken <= 0) {
            throw new IllegalArgumentException(
                    "charsPerToken must be positive, got: " + charsPerToken);
        }
        this.charsPerToken = charsPerToken;
    }

    @Override
    public long estimateTokens(ChatClientRequest request) {
        if (request == null) {
            return 0L;
        }
        Prompt prompt = request.prompt();
        if (prompt == null) {
            return 0L;
        }
        long totalChars = 0L;
        // Spring AI guarantees a non-null list of non-null Message instances on Prompt.
        // The text of an individual message can be null (e.g. multimodal messages where
        // the content is a Media list rather than text), so we skip those.
        for (Message message : prompt.getInstructions()) {
            String text = message.getText();
            if (text != null) {
                totalChars += text.length();
            }
        }
        return totalChars / charsPerToken;
    }
}
