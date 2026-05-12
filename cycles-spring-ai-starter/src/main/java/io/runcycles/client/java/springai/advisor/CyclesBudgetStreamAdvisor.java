package io.runcycles.client.java.springai.advisor;

import io.runcycles.client.java.spring.client.CyclesClient;
import io.runcycles.client.java.spring.config.CyclesProperties;
import io.runcycles.client.java.springai.CyclesBudgetDeniedException;
import io.runcycles.client.java.springai.autoconfigure.CyclesSpringAiProperties;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Pre-call budget gate and post-call usage recorder for Spring AI
 * {@link org.springframework.ai.chat.client.ChatClient} streaming invocations
 * ({@code chatClient.prompt(...).stream()}).
 *
 * <p>Mirrors the lifecycle of {@link CyclesBudgetAdvisor} but adapted to the reactive
 * stream signal model:
 * <ol>
 *   <li><strong>Pre-stream</strong>: reserves budget on the Cycles server before
 *       subscribing to the upstream chain. Denials throw
 *       {@link CyclesBudgetDeniedException} <em>without</em> starting the stream.</li>
 *   <li><strong>During stream</strong>: tracks the most-recently-emitted
 *       {@link ChatClientResponse} so the final element's usage metadata is available
 *       at completion time. Most providers (OpenAI, Anthropic) populate
 *       {@code ChatResponse.Usage} only on the last chunk; some never do, in which
 *       case the lifecycle falls back to estimate-as-actual.</li>
 *   <li><strong>On complete</strong>: commits the reservation with actual amount
 *       derived from the captured last response.</li>
 *   <li><strong>On error</strong>: releases the reservation (the call effectively
 *       didn't complete from the consumer's perspective).</li>
 *   <li><strong>On cancel</strong>: releases the reservation (consumer abandoned the
 *       stream). This matches the {@code CallAdvisor} contract that a non-completed
 *       call releases rather than commits.</li>
 * </ol>
 *
 * <p>The reserve / commit / release plumbing is shared with the non-streaming advisor
 * and the tool-callback wrapper through the public-but-internal
 * {@link CyclesBudgetLifecycle} helper.
 */
public class CyclesBudgetStreamAdvisor implements StreamAdvisor {

    private final CyclesBudgetLifecycle lifecycle;

    /**
     * Constructs a streaming budget advisor wired to the Cycles HTTP client.
     *
     * @param cyclesClient        the Cycles HTTP client (provided by cycles-client-java-spring).
     * @param cyclesProperties    the SDK-level properties (subject defaults).
     * @param springAiProperties  the Spring AI integration properties.
     */
    public CyclesBudgetStreamAdvisor(CyclesClient cyclesClient,
                                     CyclesProperties cyclesProperties,
                                     CyclesSpringAiProperties springAiProperties) {
        this.lifecycle = new CyclesBudgetLifecycle(cyclesClient, cyclesProperties, springAiProperties);
    }

    @Override
    public String getName() {
        return "cycles-budget-stream";
    }

    @Override
    public int getOrder() {
        // Match the non-streaming advisor's precedence: deny before any cost-incurring
        // downstream advisor.
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        // Reserve before subscribing to the upstream. May throw CyclesBudgetDeniedException
        // or IllegalStateException (fail-closed); both propagate to the caller without
        // engaging the stream lifecycle.
        String reservationId = lifecycle.reserveOrFailOpen(request);

        // Track the most-recently-seen element so we have a chance to extract usage
        // from the final chunk. AtomicReference because Reactor signals can fire on
        // any thread depending on the scheduler.
        AtomicReference<ChatClientResponse> lastResponse = new AtomicReference<>();

        return chain.nextStream(request)
                .doOnNext(lastResponse::set)
                .doOnError(error -> {
                    if (reservationId != null) {
                        lifecycle.releaseQuietly(reservationId,
                                "chat-stream-failed: " + error.getClass().getSimpleName());
                    }
                })
                .doOnCancel(() -> {
                    if (reservationId != null) {
                        lifecycle.releaseQuietly(reservationId, "chat-stream-cancelled");
                    }
                })
                .doFinally(signal -> {
                    // Commit only on natural completion. ON_ERROR / CANCEL already released
                    // via their dedicated callbacks; this branch ignores them.
                    if (reservationId != null && signal == SignalType.ON_COMPLETE) {
                        lifecycle.commitOrFailOpen(reservationId, lastResponse.get());
                    }
                });
    }
}
