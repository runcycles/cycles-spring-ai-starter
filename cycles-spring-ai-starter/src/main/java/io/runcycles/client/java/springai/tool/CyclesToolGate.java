package io.runcycles.client.java.springai.tool;

import io.runcycles.client.java.spring.client.CyclesClient;
import io.runcycles.client.java.spring.config.CyclesProperties;
import io.runcycles.client.java.springai.autoconfigure.CyclesSpringAiProperties;
import org.springframework.ai.tool.ToolCallback;

/**
 * Factory for {@link CyclesToolCallback} wrappers. Auto-configured as a Spring bean so
 * users can inject it where they construct their tools:
 *
 * <pre>{@code
 * @Autowired CyclesToolGate cyclesToolGate;
 *
 * @Bean
 * public ToolCallback getWeatherTool() {
 *     ToolCallback raw = MethodToolCallback.builder()
 *         .toolDefinition(...)
 *         .toolMethod(...)
 *         .build();
 *     return cyclesToolGate.wrap(raw);
 * }
 * }</pre>
 *
 * <p>Unlike the chat advisors, tool gating is opt-in — Spring AI does not provide a
 * hook to auto-decorate every registered tool, so users explicitly choose which tools
 * to gate.
 */
public class CyclesToolGate {

    private final CyclesClient cyclesClient;
    private final CyclesProperties cyclesProperties;
    private final CyclesSpringAiProperties springAiProperties;

    /**
     * Constructs the tool gate factory.
     *
     * @param cyclesClient       Cycles HTTP client.
     * @param cyclesProperties   SDK-level configuration (subject defaults).
     * @param springAiProperties Spring AI integration configuration.
     */
    public CyclesToolGate(CyclesClient cyclesClient,
                          CyclesProperties cyclesProperties,
                          CyclesSpringAiProperties springAiProperties) {
        this.cyclesClient = cyclesClient;
        this.cyclesProperties = cyclesProperties;
        this.springAiProperties = springAiProperties;
    }

    /**
     * Wrap a tool callback with Cycles budget gating.
     *
     * @param toolCallback the raw tool callback to wrap.
     * @return a {@link CyclesToolCallback} that reserves before each invocation,
     *         commits on success, releases on exception.
     */
    public CyclesToolCallback wrap(ToolCallback toolCallback) {
        return new CyclesToolCallback(toolCallback, cyclesClient, cyclesProperties, springAiProperties);
    }
}
