package io.runcycles.client.java.springai.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the Cycles Spring AI integration.
 *
 * <p>Keys are bound under {@code cycles.spring-ai.*}. Connection settings
 * ({@code cycles.base-url}, {@code cycles.api-key}) and subject defaults
 * ({@code cycles.tenant}, {@code cycles.workspace}, {@code cycles.app}) are bound by
 * the underlying {@code cycles-client-java-spring} starter and are not duplicated here.
 */
@ConfigurationProperties(prefix = "cycles.spring-ai")
public class CyclesSpringAiProperties {

    /**
     * Master switch. When false, no Cycles auto-configured beans register and Spring AI
     * passes through unchanged.
     */
    private boolean enabled = true;

    /**
     * Default estimated cost per ChatClient invocation, in the unit configured by
     * {@link #estimateUnit}. Used as the pre-call reservation amount unless
     * {@link #estimateFromPrompt} is enabled (in which case the estimate is derived
     * per-call from prompt size) and falls back here when prompt-based derivation
     * isn't applicable.
     *
     * <p>The actual amount committed after a successful call is derived from
     * {@code ChatResponse.Usage} when {@link #inputCostPerToken} /
     * {@link #outputCostPerToken} (or unit=TOKENS) provide a token-to-cost mapping;
     * otherwise the estimate is committed as actual.
     */
    private long defaultEstimate = 1000L;

    /**
     * Unit of measurement for {@link #defaultEstimate}. Must match a Cycles
     * {@code Unit} enum value (USD_MICROCENTS, TOKENS, CREDITS, RISK_POINTS).
     * Default: {@code USD_MICROCENTS}.
     */
    private String estimateUnit = "USD_MICROCENTS";

    /**
     * Action kind reported to Cycles for ChatClient invocations.
     * Default: {@code llm.chat}.
     */
    private String actionKind = "llm.chat";

    /**
     * Action name reported to Cycles for ChatClient invocations.
     * Default: {@code spring-ai-chat}.
     */
    private String actionName = "spring-ai-chat";

    /**
     * Action kind reported to Cycles for tool invocations gated through
     * {@link io.runcycles.client.java.springai.tool.CyclesToolCallback}.
     * Default: {@code tool.call}.
     */
    private String toolActionKind = "tool.call";

    /**
     * Action name prefix reported to Cycles for tool invocations. The wrapped tool's
     * name (from its {@code ToolDefinition}) is appended to this prefix so audit
     * history shows e.g. {@code spring-ai-tool:get_weather}.
     * Default: {@code spring-ai-tool:}.
     */
    private String toolActionNamePrefix = "spring-ai-tool:";

    /**
     * When true, advisor errors (e.g. Cycles server unreachable) are logged and the
     * call proceeds. When false, the advisor surfaces the error to the caller.
     * Budget denials ({@link io.runcycles.client.java.springai.CyclesBudgetDeniedException})
     * are always surfaced regardless of this setting — fail-open only applies to
     * transport / unexpected errors, not to deliberate denials.
     */
    private boolean failOpen = false;

    /**
     * Per-token cost for input (prompt) tokens, in the unit configured by
     * {@link #estimateUnit}. Used to compute the actual amount at commit time
     * from the {@code ChatResponse.Usage} returned by the LLM provider.
     *
     * <p>Default {@code 0} preserves the v0.1.0 behavior of committing the estimate
     * as actual. Set this (and {@link #outputCostPerToken}) to opt into real
     * token-based actual accounting. Ignored when the response has no usage data
     * (the advisor falls back to estimate-as-actual in that case).
     *
     * <p>Example: OpenAI gpt-4o input pricing is roughly $2.50 / 1M tokens =
     * 25 USD_MICROCENTS per input token, so {@code input-cost-per-token: 25}.
     */
    private long inputCostPerToken = 0L;

    /**
     * Per-token cost for output (completion) tokens, in the unit configured by
     * {@link #estimateUnit}. Used to compute the actual amount at commit time
     * from the {@code ChatResponse.Usage} returned by the LLM provider.
     *
     * <p>Default {@code 0} preserves the v0.1.0 behavior of committing the estimate
     * as actual. Set this (and {@link #inputCostPerToken}) to opt into real
     * token-based actual accounting.
     *
     * <p>Example: OpenAI gpt-4o output pricing is roughly $10.00 / 1M tokens =
     * 100 USD_MICROCENTS per output token, so {@code output-cost-per-token: 100}.
     */
    private long outputCostPerToken = 0L;

    /**
     * When true, derive the pre-call reservation amount from the prompt size instead
     * of using {@link #defaultEstimate}. Requires {@link #inputCostPerToken} and/or
     * {@link #outputCostPerToken} to be set; without rates, falls back to the default
     * estimate.
     *
     * <p>The actual prompt-to-tokens mapping is delegated to the configured
     * {@code PromptTokenEstimator} bean. The auto-configured default is a chars/4
     * heuristic ({@code CharsPerTokenEstimator}) that approximates OpenAI BPE on
     * English text. For tighter estimates, either set
     * {@link #tokenEstimatorEncoding} to opt in to jtokkit's real BPE encoding, or
     * supply your own {@code PromptTokenEstimator} bean.
     *
     * <p>Reservation amount: {@code estimatedInputTokens × (inputRate + outputRate)},
     * assuming output token count is comparable to input. This is intentionally
     * conservative-leaning so the reservation rarely under-shoots the actual commit.
     */
    private boolean estimateFromPrompt = false;

    /**
     * jtokkit encoding name to use for prompt token estimation. When non-null AND
     * jtokkit is on the classpath, the auto-configuration registers a
     * {@code JtokkitPromptTokenEstimator} as the {@code PromptTokenEstimator} bean
     * (overriding the chars/4 default). When null (the default), the chars/4 estimator
     * stays in place.
     *
     * <p>Valid values (case-insensitive): {@code cl100k_base} (gpt-3.5-turbo, gpt-4),
     * {@code o200k_base} (gpt-4o family), {@code p50k_base} / {@code p50k_edit} /
     * {@code r50k_base} (older models). The starter's own {@code @ConditionalOnClass}
     * gating means setting this without the jtokkit dep on the classpath is a no-op:
     * the chars/4 estimator stays, and a startup-time WARN log surfaces the misconfig.
     *
     * <p>Example:
     *
     * <pre>{@code
     * cycles:
     *   spring-ai:
     *     estimate-from-prompt: true
     *     input-cost-per-token: 25
     *     output-cost-per-token: 100
     *     token-estimator-encoding: o200k_base
     * }</pre>
     */
    private String tokenEstimatorEncoding = null;

    /** Default constructor for Spring property binding. */
    public CyclesSpringAiProperties() {
        // Spring instantiates this via reflection; defaults are set on field declarations.
    }

    /**
     * Returns whether the Cycles Spring AI integration is enabled.
     *
     * @return true when enabled.
     */
    public boolean isEnabled() { return enabled; }

    /**
     * Sets the master enable switch.
     *
     * @param enabled set false to bypass Cycles entirely.
     */
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /**
     * Returns the default per-call estimate.
     *
     * @return the default estimate.
     */
    public long getDefaultEstimate() { return defaultEstimate; }

    /**
     * Sets the default per-call estimate. Must be non-negative — a negative reservation
     * estimate would either be rejected as malformed by the Cycles server or, worse,
     * silently treated as a budget increase, which would subvert the authority gate.
     *
     * @param defaultEstimate non-negative estimate value.
     * @throws IllegalArgumentException when {@code defaultEstimate < 0}. Spring's
     *         configuration-properties binder wraps this into a
     *         {@code ConfigurationPropertiesBindException} at app startup, so the
     *         operator sees the misconfiguration immediately rather than at first call.
     */
    public void setDefaultEstimate(long defaultEstimate) {
        if (defaultEstimate < 0) {
            throw new IllegalArgumentException(
                    "cycles.spring-ai.default-estimate must be non-negative, got: " + defaultEstimate);
        }
        this.defaultEstimate = defaultEstimate;
    }

    /**
     * Returns the estimate unit name.
     *
     * @return the unit name.
     */
    public String getEstimateUnit() { return estimateUnit; }

    /**
     * Sets the estimate unit.
     *
     * @param estimateUnit a Cycles {@code Unit} enum name.
     */
    public void setEstimateUnit(String estimateUnit) { this.estimateUnit = estimateUnit; }

    /**
     * Returns the action kind label.
     *
     * @return the action kind.
     */
    public String getActionKind() { return actionKind; }

    /**
     * Sets the action kind label.
     *
     * @param actionKind the action kind.
     */
    public void setActionKind(String actionKind) { this.actionKind = actionKind; }

    /**
     * Returns the action name label.
     *
     * @return the action name.
     */
    public String getActionName() { return actionName; }

    /**
     * Sets the action name label.
     *
     * @param actionName the action name.
     */
    public void setActionName(String actionName) { this.actionName = actionName; }

    /**
     * Returns the tool action kind label.
     *
     * @return the tool action kind.
     */
    public String getToolActionKind() { return toolActionKind; }

    /**
     * Sets the tool action kind label.
     *
     * @param toolActionKind the tool action kind.
     */
    public void setToolActionKind(String toolActionKind) { this.toolActionKind = toolActionKind; }

    /**
     * Returns the tool action name prefix.
     *
     * @return the tool action name prefix.
     */
    public String getToolActionNamePrefix() { return toolActionNamePrefix; }

    /**
     * Sets the tool action name prefix. The wrapped tool's name is appended.
     *
     * @param toolActionNamePrefix the prefix.
     */
    public void setToolActionNamePrefix(String toolActionNamePrefix) {
        this.toolActionNamePrefix = toolActionNamePrefix;
    }

    /**
     * Returns whether transport-level advisor errors are tolerated.
     *
     * @return true to fail open on transport errors.
     */
    public boolean isFailOpen() { return failOpen; }

    /**
     * Sets the fail-open behavior on transport/unexpected errors.
     *
     * @param failOpen true to log and proceed, false to surface the error.
     */
    public void setFailOpen(boolean failOpen) { this.failOpen = failOpen; }

    /**
     * Returns the input (prompt) token cost rate.
     *
     * @return the cost per input token in the configured estimate unit.
     */
    public long getInputCostPerToken() { return inputCostPerToken; }

    /**
     * Sets the input token cost rate. Must be non-negative.
     *
     * @param inputCostPerToken non-negative cost per input token.
     * @throws IllegalArgumentException when negative.
     */
    public void setInputCostPerToken(long inputCostPerToken) {
        if (inputCostPerToken < 0) {
            throw new IllegalArgumentException(
                    "cycles.spring-ai.input-cost-per-token must be non-negative, got: " + inputCostPerToken);
        }
        this.inputCostPerToken = inputCostPerToken;
    }

    /**
     * Returns the output (completion) token cost rate.
     *
     * @return the cost per output token in the configured estimate unit.
     */
    public long getOutputCostPerToken() { return outputCostPerToken; }

    /**
     * Sets the output token cost rate. Must be non-negative.
     *
     * @param outputCostPerToken non-negative cost per output token.
     * @throws IllegalArgumentException when negative.
     */
    public void setOutputCostPerToken(long outputCostPerToken) {
        if (outputCostPerToken < 0) {
            throw new IllegalArgumentException(
                    "cycles.spring-ai.output-cost-per-token must be non-negative, got: " + outputCostPerToken);
        }
        this.outputCostPerToken = outputCostPerToken;
    }

    /**
     * Returns whether reservation estimate is derived from the prompt size.
     *
     * @return true to derive from prompt char count, false to use default-estimate.
     */
    public boolean isEstimateFromPrompt() { return estimateFromPrompt; }

    /**
     * Sets whether reservation estimate is derived from the prompt size.
     *
     * @param estimateFromPrompt true to opt into prompt-based estimation.
     */
    public void setEstimateFromPrompt(boolean estimateFromPrompt) {
        this.estimateFromPrompt = estimateFromPrompt;
    }

    /**
     * Returns the jtokkit encoding name (or null if unset).
     *
     * @return the encoding name, or null.
     */
    public String getTokenEstimatorEncoding() { return tokenEstimatorEncoding; }

    /**
     * Sets the jtokkit encoding name to use for prompt-token estimation.
     *
     * @param tokenEstimatorEncoding the encoding name (e.g. "cl100k_base") or null
     *                               to use the chars/4 default.
     */
    public void setTokenEstimatorEncoding(String tokenEstimatorEncoding) {
        this.tokenEstimatorEncoding = tokenEstimatorEncoding;
    }
}
