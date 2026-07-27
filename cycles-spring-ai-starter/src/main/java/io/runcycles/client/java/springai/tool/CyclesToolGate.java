package io.runcycles.client.java.springai.tool;

import io.runcycles.client.java.spring.client.CyclesClient;
import io.runcycles.client.java.spring.config.CyclesProperties;
import io.runcycles.client.java.spring.retry.CommitRetryEngine;
import io.runcycles.client.java.spring.retry.JournaledCommitRetryEngine;
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
    private final CommitRetryEngine retryEngine;

    /**
     * Constructs the tool gate factory with explicit subject-resolver and
     * commit-retry strategies. Preferred constructor — wired by the auto-configuration
     * so every wrapped tool shares the Spring-managed durable retry engine.
     *
     * @param cyclesClient       Cycles HTTP client.
     * @param cyclesProperties   SDK-level configuration.
     * @param springAiProperties Spring AI integration configuration.
     * @param subjectResolver    resolves the Cycles subject for each tool reservation.
     *                           Tool callbacks don't carry a {@code ChatClientRequest};
     *                           the resolver is invoked with {@code null} on the tool path.
     * @param retryEngine        durable retry engine that owns failed commits.
     */
    public CyclesToolGate(CyclesClient cyclesClient,
                          CyclesProperties cyclesProperties,
                          CyclesSpringAiProperties springAiProperties,
                          SubjectResolver subjectResolver,
                          CommitRetryEngine retryEngine) {
        this.cyclesClient = cyclesClient;
        this.cyclesProperties = cyclesProperties;
        this.springAiProperties = springAiProperties;
        this.subjectResolver = subjectResolver;
        this.retryEngine = retryEngine;
    }

    /**
     * Backward-compatible constructor — builds ONE private durable retry engine for
     * this gate instance; every tool wrapped by this gate shares it. Prefer the
     * engine-injecting constructor in Spring apps.
     *
     * @param cyclesClient       Cycles HTTP client.
     * @param cyclesProperties   SDK-level configuration.
     * @param springAiProperties Spring AI integration configuration.
     * @param subjectResolver    resolves the Cycles subject for each tool reservation.
     *                           Tool callbacks don't carry a {@code ChatClientRequest};
     *                           the resolver is invoked with {@code null} on the tool path.
     * @deprecated <strong>Constructs a LIVE {@link JournaledCommitRetryEngine} at
     *             gate construction</strong>: with production-shaped
     *             {@code CyclesProperties} it writes an on-disk journal under the
     *             real user home ({@code ~/.runcycles}) and performs a once-per-JVM
     *             startup replay of pending records — in unit tests a mocked client
     *             could replay and discard real pending commits. Tests should use
     *             the engine-injecting constructor with a mock
     *             {@link CommitRetryEngine}.
     */
    @Deprecated
    public CyclesToolGate(CyclesClient cyclesClient,
                          CyclesProperties cyclesProperties,
                          CyclesSpringAiProperties springAiProperties,
                          SubjectResolver subjectResolver) {
        // ONE engine per gate instance (not one per wrap() call): a per-wrap engine
        // would multiply journal replays and retry threads by the number of wrapped
        // tools. Durability stays ON by default for direct-instantiation users.
        this(cyclesClient, cyclesProperties, springAiProperties, subjectResolver,
                new JournaledCommitRetryEngine(cyclesClient, cyclesProperties));
    }

    /**
     * Backward-compatible constructor — uses the property-derived default resolver.
     *
     * @param cyclesClient       Cycles HTTP client.
     * @param cyclesProperties   SDK-level configuration.
     * @param springAiProperties Spring AI integration configuration.
     * @deprecated <strong>Constructs a LIVE {@link JournaledCommitRetryEngine} at
     *             gate construction</strong> (writes to {@code ~/.runcycles},
     *             performs a once-per-JVM startup replay with production-shaped
     *             properties). Tests should use the engine-injecting constructor
     *             with a mock {@link CommitRetryEngine}.
     */
    @Deprecated
    public CyclesToolGate(CyclesClient cyclesClient,
                          CyclesProperties cyclesProperties,
                          CyclesSpringAiProperties springAiProperties) {
        this(cyclesClient, cyclesProperties, springAiProperties,
                new PropertiesSubjectResolver(cyclesProperties));
    }

    /**
     * Wrap a tool callback with Cycles budget gating. Every wrapped tool shares this
     * gate's {@link CommitRetryEngine} (Spring-managed via the preferred constructor,
     * or the single per-gate private engine on the deprecated constructors).
     *
     * @param toolCallback the raw tool callback to wrap.
     * @return a {@link CyclesToolCallback} that reserves before each invocation,
     *         commits on success, releases on exception.
     */
    public CyclesToolCallback wrap(ToolCallback toolCallback) {
        return new CyclesToolCallback(toolCallback, cyclesClient, cyclesProperties,
                springAiProperties, subjectResolver, retryEngine);
    }
}
