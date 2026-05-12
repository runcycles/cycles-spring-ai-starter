package io.runcycles.client.java.springai.advisor;

import io.runcycles.client.java.spring.client.CyclesClient;
import io.runcycles.client.java.spring.config.CyclesProperties;
import io.runcycles.client.java.spring.model.Action;
import io.runcycles.client.java.spring.model.Amount;
import io.runcycles.client.java.spring.model.CommitRequest;
import io.runcycles.client.java.spring.model.CyclesResponse;
import io.runcycles.client.java.spring.model.ReleaseRequest;
import io.runcycles.client.java.spring.model.ReservationCreateRequest;
import io.runcycles.client.java.spring.model.ReservationResult;
import io.runcycles.client.java.spring.model.Subject;
import io.runcycles.client.java.spring.model.Unit;
import io.runcycles.client.java.springai.CyclesBudgetDeniedException;
import io.runcycles.client.java.springai.autoconfigure.CyclesSpringAiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.Ordered;

import java.util.Map;
import java.util.UUID;

/**
 * Pre-call budget gate and post-call usage recorder for Spring AI
 * {@link org.springframework.ai.chat.client.ChatClient} invocations.
 *
 * <p>For every {@code chatClient.prompt(...).call()} invocation, this advisor:
 * <ol>
 *   <li><strong>Reserves</strong> budget on the Cycles server before delegating
 *       to the next advisor in the chain.</li>
 *   <li>If the reservation is denied, throws {@link CyclesBudgetDeniedException}
 *       <em>without</em> executing the underlying call.</li>
 *   <li>If the reservation is allowed, calls the chain; on success, commits the
 *       reservation; on exception, releases the reservation.</li>
 * </ol>
 *
 * <p><strong>Streaming is not covered.</strong> This advisor implements
 * {@link CallAdvisor} only. Streaming invocations (
 * {@code chatClient.prompt(...).stream()}) use Spring AI's separate
 * {@code StreamAdvisor} plumbing and are NOT gated by Cycles in v0.1.0 — see
 * the README "Known limitations" section. {@code CyclesBudgetStreamAdvisor}
 * is planned for v0.2.
 *
 * <p><strong>Estimates are a fixed constant in v0.1.0.</strong> The reservation
 * uses {@link CyclesSpringAiProperties#getDefaultEstimate()} per call. A per-call
 * estimate derived from prompt token count + model pricing lands in v0.2.
 *
 * <p><strong>Commits use the estimate as actual.</strong> Token-usage extraction
 * from {@link ChatClientResponse} varies by provider and is not portable across
 * Spring AI model adapters in v0.1.0. The commit therefore records the estimate
 * as actual usage. v0.2 will read {@code ChatResponse.getMetadata().getUsage()}
 * and record real token counts.
 */
public class CyclesBudgetAdvisor implements CallAdvisor {

    private static final Logger log = LoggerFactory.getLogger(CyclesBudgetAdvisor.class);

    private final CyclesClient cyclesClient;
    private final CyclesProperties cyclesProperties;
    private final CyclesSpringAiProperties springAiProperties;

    /**
     * Constructs a budget advisor wired to the Cycles HTTP client and configuration.
     *
     * @param cyclesClient        the Cycles HTTP client (provided by cycles-client-java-spring).
     * @param cyclesProperties    the SDK-level properties (subject defaults: tenant/workspace/app).
     * @param springAiProperties  the Spring AI integration properties (estimate, fail-open, etc.).
     */
    public CyclesBudgetAdvisor(CyclesClient cyclesClient,
                               CyclesProperties cyclesProperties,
                               CyclesSpringAiProperties springAiProperties) {
        this.cyclesClient = cyclesClient;
        this.cyclesProperties = cyclesProperties;
        this.springAiProperties = springAiProperties;
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
        String reservationId = reserveOrFailOpen();

        // The try block ONLY wraps chain.nextCall. If that throws, we release the
        // reservation because the LLM call did not happen. If commit throws AFTER
        // chain.nextCall succeeded, we do NOT release — the budget was already
        // consumed by a successful provider call, and releasing would un-bill it.
        ChatClientResponse response;
        try {
            response = chain.nextCall(request);
        } catch (RuntimeException callFailure) {
            if (reservationId != null) {
                releaseQuietly(reservationId, "chat-call-failed: " + callFailure.getClass().getSimpleName());
            }
            throw callFailure;
        }

        if (reservationId != null) {
            commitOrFailOpen(reservationId, response);
        }
        return response;
    }

    /**
     * Creates a Cycles reservation for the upcoming chat call.
     *
     * @return the reservation id when the call should proceed, or null when fail-open is
     *         engaged and no reservation was created.
     * @throws CyclesBudgetDeniedException when the Cycles server denies the call.
     */
    private String reserveOrFailOpen() {
        ReservationCreateRequest req = buildReservationRequest();
        CyclesResponse<Map<String, Object>> response;
        try {
            response = cyclesClient.createReservation(req);
        } catch (RuntimeException transportFailure) {
            return handleReserveTransportFailure(transportFailure);
        }

        if (!response.is2xx()) {
            return handleReserveHttpFailure(response);
        }

        ReservationResult result = ReservationResult.fromMap(response.getBody());
        if (result == null) {
            return handleReserveHttpFailure(response);
        }

        if (result.isDenied()) {
            throw new CyclesBudgetDeniedException(
                    "Cycles denied Spring AI chat call: reason=" + result.getReasonCode()
                            + " scope=" + result.getScopePath(),
                    result.getReasonCode(),
                    result.getScopePath());
        }

        // Defensive: a 2xx with an unrecognized decision or missing reservation_id
        // must NOT silently bypass the budget gate. Treat as malformed HTTP failure
        // so fail-open / fail-closed applies the same way as a 5xx.
        String reservationId = result.getReservationId();
        if (!result.isAllowed() || reservationId == null || reservationId.isBlank()) {
            log.warn("Cycles reservation 2xx response was malformed: decision={} reservation_id={}",
                    result.getDecision(), reservationId);
            return handleReserveHttpFailure(response);
        }
        return reservationId;
    }

    private String handleReserveTransportFailure(RuntimeException cause) {
        if (springAiProperties.isFailOpen()) {
            log.warn("Cycles reservation transport failure (fail-open=true; proceeding without budget gate): {}",
                    cause.getMessage());
            return null;
        }
        throw new IllegalStateException("Cycles reservation failed (fail-open=false)", cause);
    }

    private String handleReserveHttpFailure(CyclesResponse<Map<String, Object>> response) {
        if (springAiProperties.isFailOpen()) {
            log.warn("Cycles reservation HTTP failure status={} (fail-open=true; proceeding): body={}",
                    response.getStatus(), response.getBody());
            return null;
        }
        throw new IllegalStateException(
                "Cycles reservation HTTP failure status=" + response.getStatus());
    }

    private void commitOrFailOpen(String reservationId, ChatClientResponse chatResponse) {
        CommitRequest commit = CommitRequest.builder()
                .idempotencyKey(UUID.randomUUID().toString())
                .actual(buildActualAmount(chatResponse))
                .build();
        CyclesResponse<Map<String, Object>> commitResponse;
        try {
            commitResponse = cyclesClient.commitReservation(reservationId, commit);
        } catch (RuntimeException transportFailure) {
            if (springAiProperties.isFailOpen()) {
                log.warn("Cycles commit transport failure (fail-open=true; ignoring): {}",
                        transportFailure.getMessage());
                return;
            }
            throw new IllegalStateException(
                    "Cycles commit failed for reservation " + reservationId + " (fail-open=false)",
                    transportFailure);
        }
        if (!commitResponse.is2xx()) {
            if (springAiProperties.isFailOpen()) {
                log.warn("Cycles commit HTTP failure status={} for reservation {} "
                                + "(fail-open=true; ignoring): body={}",
                        commitResponse.getStatus(), reservationId, commitResponse.getBody());
                return;
            }
            throw new IllegalStateException(
                    "Cycles commit HTTP failure status=" + commitResponse.getStatus()
                            + " for reservation " + reservationId);
        }
    }

    private void releaseQuietly(String reservationId, String reason) {
        ReleaseRequest release = ReleaseRequest.builder()
                .idempotencyKey(UUID.randomUUID().toString())
                .reason(reason)
                .build();
        try {
            CyclesResponse<Map<String, Object>> response = cyclesClient.releaseReservation(reservationId, release);
            // CyclesClient implementations return CyclesResponse with httpError() on
            // 4xx/5xx rather than throwing, so we must inspect the status to surface
            // failures. Reservation will TTL-expire on the server even if release
            // fails, so we only log — never throw — from this path.
            if (!response.is2xx()) {
                log.warn("Cycles release HTTP failure for reservation {} status={} body={}",
                        reservationId, response.getStatus(), response.getBody());
            }
        } catch (RuntimeException releaseFailure) {
            log.warn("Cycles release transport failure for reservation {}: {}",
                    reservationId, releaseFailure.getMessage());
        }
    }

    private ReservationCreateRequest buildReservationRequest() {
        return ReservationCreateRequest.builder()
                .idempotencyKey(UUID.randomUUID().toString())
                .subject(buildSubject())
                .action(new Action(springAiProperties.getActionKind(), springAiProperties.getActionName(), null))
                .estimate(buildEstimateAmount())
                .build();
    }

    private Subject buildSubject() {
        return Subject.builder()
                .tenant(cyclesProperties.getTenant())
                .workspace(cyclesProperties.getWorkspace())
                .app(cyclesProperties.getApp())
                .workflow(cyclesProperties.getWorkflow())
                .agent(cyclesProperties.getAgent())
                .toolset(cyclesProperties.getToolset())
                .build();
    }

    private Amount buildEstimateAmount() {
        Unit unit = Unit.fromString(springAiProperties.getEstimateUnit());
        if (unit == null) {
            unit = Unit.USD_MICROCENTS;
        }
        return new Amount(unit, springAiProperties.getDefaultEstimate());
    }

    /**
     * Compute the actual amount to commit from the chat response's token usage,
     * falling back to the estimate when usage data or rates aren't available.
     *
     * <p>Three modes, in priority order:
     * <ol>
     *   <li>{@code estimate-unit=TOKENS}: commit total tokens directly (no rate config needed).</li>
     *   <li>{@code input-cost-per-token} or {@code output-cost-per-token} configured and usage
     *       present: commit {@code (promptTokens * inputCost) + (completionTokens * outputCost)}.</li>
     *   <li>Otherwise: commit the estimate as actual (v0.1.0-compatible fallback).</li>
     * </ol>
     *
     * <p>Usage extraction tolerates null at every step — providers occasionally omit usage
     * data in non-OpenAI-shaped responses, and we don't want to throw at commit time.
     */
    private Amount buildActualAmount(ChatClientResponse response) {
        Unit unit = Unit.fromString(springAiProperties.getEstimateUnit());
        if (unit == null) {
            unit = Unit.USD_MICROCENTS;
        }

        Usage usage = extractUsage(response);

        if (unit == Unit.TOKENS && usage != null && usage.getTotalTokens() != null) {
            return new Amount(unit, usage.getTotalTokens().longValue());
        }

        long inputRate = springAiProperties.getInputCostPerToken();
        long outputRate = springAiProperties.getOutputCostPerToken();
        if (usage != null && (inputRate > 0 || outputRate > 0)) {
            long actual = (nullSafeLong(usage.getPromptTokens()) * inputRate)
                        + (nullSafeLong(usage.getCompletionTokens()) * outputRate);
            return new Amount(unit, actual);
        }

        // Fallback: commit estimate as actual (v0.1.0 behavior).
        return buildEstimateAmount();
    }

    /**
     * Defensively pull {@code Usage} out of a possibly-incomplete ChatClientResponse.
     * Caller must pass a non-null response (only invoked from {@code buildActualAmount},
     * which is reached only after {@code chain.nextCall} returned successfully).
     *
     * @return the usage or null when any intermediate step is null.
     */
    private static Usage extractUsage(ChatClientResponse response) {
        ChatResponse chatResponse = response.chatResponse();
        if (chatResponse == null) {
            return null;
        }
        ChatResponseMetadata metadata = chatResponse.getMetadata();
        if (metadata == null) {
            return null;
        }
        return metadata.getUsage();
    }

    private static long nullSafeLong(Integer value) {
        return value == null ? 0L : value.longValue();
    }
}
