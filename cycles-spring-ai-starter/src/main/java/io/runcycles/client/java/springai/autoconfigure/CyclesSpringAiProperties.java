package io.runcycles.client.java.springai.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the Cycles Spring AI integration.
 *
 * <p>Keys are bound under {@code cycles.spring-ai.*}.
 */
@ConfigurationProperties(prefix = "cycles.spring-ai")
public class CyclesSpringAiProperties {

    /**
     * Master switch. When false, no Cycles auto-configured beans register and Spring AI
     * passes through unchanged.
     */
    private boolean enabled = true;

    /**
     * The Cycles budget identifier this application's calls are charged against.
     * Required when {@link #enabled} is true.
     */
    private String budgetId;

    /**
     * Cycles server URL.
     */
    private String serverUrl = "http://localhost:8080";

    /**
     * When true, advisor errors (e.g., Cycles server unreachable) are logged and the call
     * proceeds. When false, the advisor surfaces the error to the caller.
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
     * Returns the Cycles budget identifier, or null when unset.
     *
     * @return the budget id.
     */
    public String getBudgetId() { return budgetId; }

    /**
     * Sets the Cycles budget identifier to charge calls against.
     *
     * @param budgetId the budget id.
     */
    public void setBudgetId(String budgetId) { this.budgetId = budgetId; }

    /**
     * Returns the Cycles server URL.
     *
     * @return the server URL.
     */
    public String getServerUrl() { return serverUrl; }

    /**
     * Sets the Cycles server URL this application talks to.
     *
     * @param serverUrl the server URL.
     */
    public void setServerUrl(String serverUrl) { this.serverUrl = serverUrl; }

    /**
     * Returns whether advisor errors are tolerated (log + proceed) or surfaced.
     *
     * @return true to fail open.
     */
    public boolean isFailOpen() { return failOpen; }

    /**
     * Sets the fail-open behavior on Cycles errors.
     *
     * @param failOpen true to log and proceed, false to surface the error.
     */
    public void setFailOpen(boolean failOpen) { this.failOpen = failOpen; }
}
