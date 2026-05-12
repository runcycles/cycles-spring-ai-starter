package io.runcycles.client.java.springai.advisor;

import io.runcycles.client.java.spring.client.CyclesClient;
import io.runcycles.client.java.spring.config.CyclesProperties;
import io.runcycles.client.java.springai.CyclesBudgetDeniedException;
import io.runcycles.client.java.springai.autoconfigure.CyclesSpringAiProperties;
import io.runcycles.client.java.springai.subject.SubjectResolver;
import io.runcycles.client.java.springai.tokenizer.PromptTokenEstimator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

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
     * Constructs a streaming budget advisor with explicit subject and token-estimator
     * strategies. Preferred constructor — wired by the auto-configuration.
     *
     * @param cyclesClient        the Cycles HTTP client.
     * @param cyclesProperties    the SDK-level properties.
     * @param springAiProperties  the Spring AI integration properties.
     * @param subjectResolver     resolves the Cycles subject for each reservation.
     * @param tokenEstimator      estimates prompt tokens for prompt-based reservation sizing.
     */
    public CyclesBudgetStreamAdvisor(CyclesClient cyclesClient,
                                     CyclesProperties cyclesProperties,
                                     CyclesSpringAiProperties springAiProperties,
                                     SubjectResolver subjectResolver,
                                     PromptTokenEstimator tokenEstimator) {
        this.lifecycle = new CyclesBudgetLifecycle(cyclesClient, cyclesProperties,
                springAiProperties, subjectResolver, tokenEstimator);
    }

    /**
     * Backward-compatible constructor — uses the default chars-per-token estimator
     * with the supplied subject resolver.
     *
     * @param cyclesClient        the Cycles HTTP client.
     * @param cyclesProperties    the SDK-level properties.
     * @param springAiProperties  the Spring AI integration properties.
     * @param subjectResolver     resolves the Cycles subject.
     */
    public CyclesBudgetStreamAdvisor(CyclesClient cyclesClient,
                                     CyclesProperties cyclesProperties,
                                     CyclesSpringAiProperties springAiProperties,
                                     SubjectResolver subjectResolver) {
        this.lifecycle = new CyclesBudgetLifecycle(cyclesClient, cyclesProperties,
                springAiProperties, subjectResolver);
    }

    /**
     * Backward-compatible constructor that uses the default property-derived subject
     * resolver. Kept for callers that instantiate the advisor directly.
     *
     * @param cyclesClient        the Cycles HTTP client.
     * @param cyclesProperties    the SDK-level properties.
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
        // Wrap the entire reserve → stream → commit/release chain in Flux.defer so:
        //
        //  1. The reservation is created per subscription, not at assembly time. A caller
        //     that builds the Flux but never subscribes does not leak a reservation; a
        //     caller that resubscribes gets a fresh reservation per subscription rather
        //     than reusing the same id across subscriptions.
        //  2. CyclesBudgetDeniedException and IllegalStateException from reserveOrFailOpen
        //     surface as onError signals (the reactive-idiomatic shape for a Flux<T>
        //     pipeline). Subscribers can branch with .onErrorResume(...) and friends.
        return Flux.defer(() -> {
            String reservationId = lifecycle.reserveOrFailOpen(request);

            // Track the most-recently-seen element so we have a chance to extract usage
            // from the final chunk. AtomicReference because Reactor signals can fire on
            // any thread depending on the scheduler.
            AtomicReference<ChatClientResponse> lastResponse = new AtomicReference<>();

            Flux<ChatClientResponse> upstream;
            try {
                upstream = chain.nextStream(request);
            } catch (RuntimeException assemblyFailure) {
                // The downstream advisor threw before producing a Flux. We hold a live
                // reservation with no stream lifecycle to attach to — release it so it
                // doesn't TTL-expire on the server.
                if (reservationId != null) {
                    lifecycle.releaseQuietly(reservationId,
                            "chat-stream-assembly-failed: " + assemblyFailure.getClass().getSimpleName());
                }
                throw assemblyFailure;
            }

            return upstream
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
                    // Commit AFTER upstream completes successfully but BEFORE onComplete is
                    // delivered to the downstream subscriber. doFinally fires after the
                    // subscriber has already observed completion, which means a commit
                    // failure cannot fail the stream the way the non-streaming advisor
                    // fails its call. concatWith with a Mono.defer that commits and
                    // returns Mono.empty() (or Mono.error on commit failure) puts the
                    // commit ON the reactive timeline, so commit failures in fail-closed
                    // mode propagate as onError to subscribers correctly.
                    .concatWith(Mono.<ChatClientResponse>defer(() -> {
                        if (reservationId == null) {
                            return Mono.empty();
                        }
                        try {
                            lifecycle.commitOrFailOpen(reservationId, lastResponse.get());
                            return Mono.empty();
                        } catch (RuntimeException commitFailure) {
                            return Mono.error(commitFailure);
                        }
                    }));
        });
    }
}
