package io.runcycles.client.java.springai.tokenizer;

import org.springframework.ai.chat.client.ChatClientRequest;

/**
 * Estimates how many tokens a prompt will consume on the upstream LLM provider.
 *
 * <p>Used by {@code CyclesBudgetLifecycle} when
 * {@code cycles.spring-ai.estimate-from-prompt=true} to compute a per-call reservation
 * amount: {@code estimateTokens(request) × (inputRate + outputRate)}.
 *
 * <p>The default auto-configured implementation ({@code CharsPerTokenEstimator})
 * approximates tokens as {@code prompt-chars / 4}, which is reasonable for English
 * text on OpenAI-family BPE tokenizers but drifts on other languages and structured
 * content. For tighter estimates, supply your own bean — typically a wrapper around
 * a real tokenizer such as jtokkit (OpenAI BPE) or a provider-specific tokenizer.
 * {@link org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean}
 * backs the default off when a user bean is present.
 *
 * <p>Implementations should handle the cases where {@link ChatClientRequest#prompt()}
 * is null, the prompt has no messages, or individual messages have null text (e.g.
 * multimodal messages with media but no text content). Returning 0 in any of those
 * cases is correct — the lifecycle falls back to {@code default-estimate} when the
 * estimated tokens are 0.
 */
@FunctionalInterface
public interface PromptTokenEstimator {

    /**
     * Estimates the input-side token count for the given chat request's prompt.
     *
     * @param request the originating chat request; never {@code null} when invoked
     *                from the chat advisors. (Tool-gating paths skip prompt estimation
     *                entirely, so the tool path doesn't call this method.)
     * @return non-negative estimated token count. Return 0 when the prompt is empty
     *         or no text content is available.
     */
    long estimateTokens(ChatClientRequest request);
}
