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

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getBudgetId() { return budgetId; }
    public void setBudgetId(String budgetId) { this.budgetId = budgetId; }

    public String getServerUrl() { return serverUrl; }
    public void setServerUrl(String serverUrl) { this.serverUrl = serverUrl; }

    public boolean isFailOpen() { return failOpen; }
    public void setFailOpen(boolean failOpen) { this.failOpen = failOpen; }
}
