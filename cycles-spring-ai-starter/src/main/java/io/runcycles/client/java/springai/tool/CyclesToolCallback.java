package io.runcycles.client.java.springai.tool;

import io.runcycles.client.java.spring.client.CyclesClient;
import io.runcycles.client.java.spring.config.CyclesProperties;
import io.runcycles.client.java.springai.CyclesBudgetDeniedException;
import io.runcycles.client.java.springai.advisor.CyclesBudgetLifecycle;
import io.runcycles.client.java.springai.autoconfigure.CyclesSpringAiProperties;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Decorator that gates a Spring AI {@link ToolCallback} invocation with a Cycles
 * reservation. Each tool call reserves before execution, commits on success, and
 * releases on exception — same lifecycle as the chat advisors, but scoped per
 * tool invocation rather than per ChatClient call.
 *
 * <p>Unlike the call/stream advisors, tool decoration is <strong>opt-in</strong>.
 * Spring AI does not provide a hook to auto-decorate every registered tool, so users
 * explicitly wrap the tools they want gated:
 *
 * <pre>{@code
 * ToolCallback rawTool = ...;
 * CyclesToolCallback gatedTool = cyclesToolGate.wrap(rawTool);
 * chatClient.prompt("...").tools(gatedTool).call();
 * }</pre>
 *
 * <p>See {@link CyclesToolGate} for a factory convenience.
 *
 * <p>Action labels reported to Cycles for the tool reservation come from
 * {@code cycles.spring-ai.tool-action-kind} (default {@code tool.call}) and
 * {@code cycles.spring-ai.tool-action-name-prefix} (default {@code spring-ai-tool:}),
 * with the wrapped tool's name appended to the prefix.
 *
 * <p>Estimate logic mirrors the chat advisors: when {@code estimate-from-prompt=true},
 * the tool reservation amount tries prompt-based derivation; otherwise it falls back
 * to {@code default-estimate}. (Tool callbacks don't receive a ChatClientRequest, so
 * prompt-based estimation effectively falls back to default-estimate by design.)
 */
public class CyclesToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final CyclesBudgetLifecycle lifecycle;
    private final CyclesSpringAiProperties springAiProperties;

    /**
     * Constructs a Cycles-gated wrapper around a Spring AI ToolCallback.
     *
     * @param delegate           the tool callback to wrap.
     * @param cyclesClient       the Cycles HTTP client.
     * @param cyclesProperties   SDK-level properties (subject defaults).
     * @param springAiProperties Spring AI integration properties.
     */
    public CyclesToolCallback(ToolCallback delegate,
                              CyclesClient cyclesClient,
                              CyclesProperties cyclesProperties,
                              CyclesSpringAiProperties springAiProperties) {
        this.delegate = delegate;
        this.lifecycle = new CyclesBudgetLifecycle(cyclesClient, cyclesProperties, springAiProperties);
        this.springAiProperties = springAiProperties;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return gateInvocation(() -> delegate.call(toolInput));
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return gateInvocation(() -> delegate.call(toolInput, toolContext));
    }

    /**
     * Run the supplied tool invocation under a Cycles reservation.
     *
     * <p>Reserves before the invocation; commits on success (estimate-as-actual since
     * tools don't return ChatResponse usage); releases on RuntimeException.
     *
     * <p>{@link CyclesBudgetDeniedException} is thrown synchronously when the Cycles
     * server denies the reservation — the wrapped tool is NOT invoked.
     */
    private String gateInvocation(ToolInvocation invocation) {
        String actionName = springAiProperties.getToolActionNamePrefix() + delegate.getToolDefinition().name();
        String reservationId = lifecycle.reserveOrFailOpen(null,
                springAiProperties.getToolActionKind(), actionName);
        try {
            String result = invocation.run();
            if (reservationId != null) {
                lifecycle.commitOrFailOpen(reservationId, null);
            }
            return result;
        } catch (RuntimeException invocationFailure) {
            if (reservationId != null) {
                lifecycle.releaseQuietly(reservationId,
                        "tool-call-failed: " + invocationFailure.getClass().getSimpleName());
            }
            throw invocationFailure;
        }
    }

    @FunctionalInterface
    private interface ToolInvocation {
        String run();
    }
}
