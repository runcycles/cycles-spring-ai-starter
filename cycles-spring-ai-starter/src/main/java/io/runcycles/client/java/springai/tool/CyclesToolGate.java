package io.runcycles.client.java.springai.tool;

import io.runcycles.client.java.spring.client.CyclesClient;
import io.runcycles.client.java.spring.config.CyclesProperties;
import io.runcycles.client.java.springai.autoconfigure.CyclesSpringAiProperties;
import io.runcycles.client.java.springai.subject.PropertiesSubjectResolver;
import io.runcycles.client.java.springai.subject.SubjectResolver;
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
    private final SubjectResolver subjectResolver;

    /**
     * Constructs the tool gate factory with an explicit subject resolver.
     *
     * @param cyclesClient       Cycles HTTP client.
     * @param cyclesProperties   SDK-level configuration.
     * @param springAiProperties Spring AI integration configuration.
     * @param subjectResolver    resolves the Cycles subject for each tool reservation.
     *                           Tool callbacks don't carry a {@code ChatClientRequest};
     *                           the resolver is invoked with {@code null} on the tool path.
     */
    public CyclesToolGate(CyclesClient cyclesClient,
                          CyclesProperties cyclesProperties,
                          CyclesSpringAiProperties springAiProperties,
                          SubjectResolver subjectResolver) {
        this.cyclesClient = cyclesClient;
        this.cyclesProperties = cyclesProperties;
        this.springAiProperties = springAiProperties;
        this.subjectResolver = subjectResolver;
    }

    /**
     * Backward-compatible constructor — uses the property-derived default resolver.
     *
     * @param cyclesClient       Cycles HTTP client.
     * @param cyclesProperties   SDK-level configuration.
     * @param springAiProperties Spring AI integration configuration.
     */
    public CyclesToolGate(CyclesClient cyclesClient,
                          CyclesProperties cyclesProperties,
                          CyclesSpringAiProperties springAiProperties) {
        this(cyclesClient, cyclesProperties, springAiProperties,
                new PropertiesSubjectResolver(cyclesProperties));
    }

    /**
     * Wrap a tool callback with Cycles budget gating.
     *
     * @param toolCallback the raw tool callback to wrap.
     * @return a {@link CyclesToolCallback} that reserves before each invocation,
     *         commits on success, releases on exception.
     */
    public CyclesToolCallback wrap(ToolCallback toolCallback) {
        return new CyclesToolCallback(toolCallback, cyclesClient, cyclesProperties,
                springAiProperties, subjectResolver);
    }
}
