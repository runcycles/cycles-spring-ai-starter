package io.runcycles.client.java.springai.advisor;

import io.runcycles.client.java.spring.client.CyclesClient;
import io.runcycles.client.java.spring.config.CyclesProperties;
import io.runcycles.client.java.spring.retry.CommitRetryEngine;
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
     * Constructs a budget advisor with explicit subject, token-estimator, and
     * commit-retry strategies. Preferred constructor — wired by the auto-configuration
     * with the user-provided beans (each defaults to a properties-derived impl when no
     * user bean is registered) and the Spring-managed {@link CommitRetryEngine} bean
     * (so failed commits are journaled/retried durably and flushed on shutdown).
     *
     * @param cyclesClient        the Cycles HTTP client (provided by cycles-client-java-spring).
     * @param cyclesProperties    the SDK-level properties.
     * @param springAiProperties  the Spring AI integration properties.
     * @param subjectResolver     resolves the Cycles {@code Subject} for each reservation.
     * @param tokenEstimator      estimates prompt tokens for prompt-based reservation sizing.
     * @param retryEngine         durable retry engine that owns failed commits.
     */
    public CyclesBudgetAdvisor(CyclesClient cyclesClient,
                               CyclesProperties cyclesProperties,
                               CyclesSpringAiProperties springAiProperties,
                               SubjectResolver subjectResolver,
                               PromptTokenEstimator tokenEstimator,
                               CommitRetryEngine retryEngine) {
        this.lifecycle = new CyclesBudgetLifecycle(cyclesClient, cyclesProperties,
                springAiProperties, subjectResolver, tokenEstimator, retryEngine);
    }

    /**
     * Backward-compatible constructor — creates a private durable retry engine
     * internally (see {@link CyclesBudgetLifecycle}). Prefer the engine-injecting
     * constructor in Spring apps.
     *
     * @param cyclesClient        the Cycles HTTP client (provided by cycles-client-java-spring).
     * @param cyclesProperties    the SDK-level properties.
     * @param springAiProperties  the Spring AI integration properties.
     * @param subjectResolver     resolves the Cycles {@code Subject} for each reservation.
     * @param tokenEstimator      estimates prompt tokens for prompt-based reservation sizing.
     * @deprecated <strong>Constructs a LIVE journaled commit-retry engine per
     *             instance</strong>: with production-shaped {@code CyclesProperties}
     *             it writes an on-disk journal under the real user home
     *             ({@code ~/.runcycles}) and performs a once-per-JVM startup replay
     *             of pending records — in unit tests a mocked client could replay
     *             and discard real pending commits. Tests should use the
     *             engine-injecting constructor with a mock {@link CommitRetryEngine}.
     */
    @Deprecated
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
     * @deprecated <strong>Constructs a LIVE journaled commit-retry engine per
     *             instance</strong> (writes to {@code ~/.runcycles}, performs a
     *             once-per-JVM startup replay with production-shaped properties).
     *             Tests should use the engine-injecting constructor with a mock
     *             {@link CommitRetryEngine}.
     */
    @Deprecated
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
     * @deprecated <strong>Constructs a LIVE journaled commit-retry engine per
     *             instance</strong> (writes to {@code ~/.runcycles}, performs a
     *             once-per-JVM startup replay with production-shaped properties).
     *             Tests should use the engine-injecting constructor with a mock
     *             {@link CommitRetryEngine}.
     */
    @Deprecated
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
        CyclesBudgetLifecycle.ActiveReservation reservation = lifecycle.reserveOrFailOpen(request);

        // Thread the reservation_id into the request context so the
        // CyclesChatClientObservationConvention can emit it as a high-cardinality
        // KeyValue on the trace (enabling trace ↔ reservation correlation). The
        // observation reads context at stop time, which is AFTER this advisor returns,
        // so the put is observable. Skipped when the reservation is null (fail-open
        // reserve skip — no reservation to correlate).
        if (reservation != null && request != null) {
            request.context().put(CyclesObservationContextKeys.RESERVATION_ID, reservation.id());
        }

        // The try block ONLY wraps chain.nextCall. If that throws, we release the
        // reservation because the LLM call did not happen. If commit throws AFTER
        // chain.nextCall succeeded (genuine 4xx rejection in fail-closed mode — the
        // only commit failure that still throws), we do NOT release here: the
        // lifecycle has already released the rejected reservation itself (reason
        // commit_rejected_<code>) before throwing.
        ChatClientResponse response;
        try {
            response = chain.nextCall(request);
        } catch (RuntimeException callFailure) {
            if (reservation != null) {
                lifecycle.releaseQuietly(reservation.id(), "chat-call-failed: " + callFailure.getClass().getSimpleName());
            }
            throw callFailure;
        }

        if (reservation != null) {
            lifecycle.commitOrFailOpen(reservation, response);
        }
        return response;
    }
}
