package io.runcycles.client.java.springai.advisor;

import io.runcycles.client.java.spring.client.CyclesClient;
import io.runcycles.client.java.spring.config.CyclesProperties;
import io.runcycles.client.java.springai.CyclesBudgetDeniedException;
import io.runcycles.client.java.springai.autoconfigure.CyclesSpringAiProperties;
import io.runcycles.client.java.springai.observation.CyclesObservationContextKeys;
import io.runcycles.client.java.springai.subject.SubjectResolver;
import io.runcycles.client.java.springai.tokenizer.PromptTokenEstimator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.core.Ordered;

/**
 * Pre-call budget gate and post-call usage recorder for Spring AI
 * {@link org.springframework.ai.chat.client.ChatClient} non-streaming invocations.
 *
 * <p>For every {@code chatClient.prompt(...).call()} invocation, this advisor:
 * <ol>
 *   <li><strong>Reserves</strong> budget on the Cycles server before delegating
 *       to the next advisor in the chain.</li>
 *   <li>If the reservation is denied, throws {@link CyclesBudgetDeniedException}
 *       <em>without</em> executing the underlying call.</li>
 *   <li>If the reservation is allowed, calls the chain; on success, commits the
 *       reservation with actual usage derived from {@code ChatResponse.Usage}
 *       (or the estimate as fallback); on exception, releases the reservation.</li>
 * </ol>
 *
 * <p>For streaming invocations (e.g. {@code chatClient.prompt(...).stream()}), use the
 * companion {@link CyclesBudgetStreamAdvisor}.
 *
 * <p>The reserve / commit / release plumbing is shared with the stream advisor and
 * the tool-callback wrapper through the public-but-internal {@link CyclesBudgetLifecycle}
 * helper.
 */
public class CyclesBudgetAdvisor implements CallAdvisor {

    private final CyclesBudgetLifecycle lifecycle;

    /**
     * Constructs a budget advisor with explicit subject and token-estimator
     * strategies. Preferred constructor — wired by the auto-configuration with the
     * user-provided beans (each defaults to a properties-derived impl when no user
     * bean is registered).
     *
     * @param cyclesClient        the Cycles HTTP client (provided by cycles-client-java-spring).
     * @param cyclesProperties    the SDK-level properties.
     * @param springAiProperties  the Spring AI integration properties.
     * @param subjectResolver     resolves the Cycles {@code Subject} for each reservation.
     * @param tokenEstimator      estimates prompt tokens for prompt-based reservation sizing.
     */
    public CyclesBudgetAdvisor(CyclesClient cyclesClient,
                               CyclesProperties cyclesProperties,
                               CyclesSpringAiProperties springAiProperties,
                               SubjectResolver subjectResolver,
                               PromptTokenEstimator tokenEstimator) {
        this.lifecycle = new CyclesBudgetLifecycle(cyclesClient, cyclesProperties,
                springAiProperties, subjectResolver, tokenEstimator);
    }

    /**
     * Backward-compatible constructor — uses the default chars-per-token estimator
     * with the supplied subject resolver. Matches v0.2.0 prompt-estimation behavior
     * when {@code estimate-from-prompt=true}.
     *
     * @param cyclesClient        the Cycles HTTP client.
     * @param cyclesProperties    the SDK-level properties.
     * @param springAiProperties  the Spring AI integration properties.
     * @param subjectResolver     resolves the Cycles {@code Subject}.
     */
    public CyclesBudgetAdvisor(CyclesClient cyclesClient,
                               CyclesProperties cyclesProperties,
                               CyclesSpringAiProperties springAiProperties,
                               SubjectResolver subjectResolver) {
        this.lifecycle = new CyclesBudgetLifecycle(cyclesClient, cyclesProperties,
                springAiProperties, subjectResolver);
    }

    /**
     * Backward-compatible constructor that uses the default property-derived subject
     * resolver — equivalent to v0.1.0 / v0.2.0 behavior. Kept for callers that
     * instantiate the advisor directly without going through the auto-configuration.
     *
     * @param cyclesClient        the Cycles HTTP client.
     * @param cyclesProperties    the SDK-level properties.
     * @param springAiProperties  the Spring AI integration properties.
     */
    public CyclesBudgetAdvisor(CyclesClient cyclesClient,
                               CyclesProperties cyclesProperties,
                               CyclesSpringAiProperties springAiProperties) {
        this.lifecycle = new CyclesBudgetLifecycle(cyclesClient, cyclesProperties, springAiProperties);
    }

    @Override
    public String getName() {
        return "cycles-budget";
    }

    @Override
    public int getOrder() {
        // Run early so budget denial happens before any cost-incurring downstream advisor.
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        // reserveOrFailOpen may throw CyclesBudgetDeniedException (DENY decision) or
        // IllegalStateException (fail-closed transport/HTTP failure); both propagate up
        // without entering the try below, so no release happens for those — which is
        // correct (no reservation was created).
        String reservationId = lifecycle.reserveOrFailOpen(request);

        // Thread the reservation_id into the request context so the
        // CyclesChatClientObservationConvention can emit it as a high-cardinality
        // KeyValue on the trace (enabling trace ↔ reservation correlation). The
        // observation reads context at stop time, which is AFTER this advisor returns,
        // so the put is observable. Skipped when reservationId is null (fail-open
        // reserve skip — no reservation to correlate).
        if (reservationId != null && request != null) {
            request.context().put(CyclesObservationContextKeys.RESERVATION_ID, reservationId);
        }

        // The try block ONLY wraps chain.nextCall. If that throws, we release the
        // reservation because the LLM call did not happen. If commit throws AFTER
        // chain.nextCall succeeded, we do NOT release — the budget was already
        // consumed by a successful provider call, and releasing would un-bill it.
        ChatClientResponse response;
        try {
            response = chain.nextCall(request);
        } catch (RuntimeException callFailure) {
            if (reservationId != null) {
                lifecycle.releaseQuietly(reservationId, "chat-call-failed: " + callFailure.getClass().getSimpleName());
            }
            throw callFailure;
        }

        if (reservationId != null) {
            lifecycle.commitOrFailOpen(reservationId, response);
        }
        return response;
    }
}
