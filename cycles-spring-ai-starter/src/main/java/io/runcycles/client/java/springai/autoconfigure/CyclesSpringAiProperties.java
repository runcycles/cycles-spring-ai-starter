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
     * {@link #estimateUnit}. Used when a more specific per-call estimate is not
     * available. Default: 1000.
     *
     * <p>v0.2 will derive a per-call estimate from prompt token counts; until then,
     * this constant is used for every call.
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
     * When true, advisor errors (e.g. Cycles server unreachable) are logged and the
     * call proceeds. When false, the advisor surfaces the error to the caller.
     * Budget denials ({@link io.runcycles.client.java.springai.CyclesBudgetDeniedException})
     * are always surfaced regardless of this setting — fail-open only applies to
     * transport / unexpected errors, not to deliberate denials.
     */
    private boolean failOpen = false;

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
     * Sets the default per-call estimate.
     *
     * @param defaultEstimate non-negative estimate value.
     */
    public void setDefaultEstimate(long defaultEstimate) { this.defaultEstimate = defaultEstimate; }

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
}
