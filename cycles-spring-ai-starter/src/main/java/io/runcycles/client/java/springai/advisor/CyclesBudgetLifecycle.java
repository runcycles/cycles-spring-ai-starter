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
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.Map;
import java.util.UUID;

/**
 * Reserve / commit / release lifecycle shared by both the call and stream advisors.
 *
 * <p>Both {@link CyclesBudgetAdvisor} (non-streaming) and {@code CyclesBudgetStreamAdvisor}
 * (streaming) need the same reservation-against-Cycles plumbing — wire calls, fail-open
 * handling, actual-amount extraction from {@code ChatResponse.Usage}. This class collects
 * that logic so the two advisor classes are thin wrappers that supply the right reactive
 * vs imperative glue.
 *
 * <p>Package-private — not part of the public API. The public surface lives on the advisor
 * classes themselves and the {@link CyclesSpringAiProperties} configuration block.
 */
final class CyclesBudgetLifecycle {

    private static final Logger log = LoggerFactory.getLogger(CyclesBudgetLifecycle.class);

    private final CyclesClient cyclesClient;
    private final CyclesProperties cyclesProperties;
    private final CyclesSpringAiProperties springAiProperties;

    CyclesBudgetLifecycle(CyclesClient cyclesClient,
                          CyclesProperties cyclesProperties,
                          CyclesSpringAiProperties springAiProperties) {
        this.cyclesClient = cyclesClient;
        this.cyclesProperties = cyclesProperties;
        this.springAiProperties = springAiProperties;
    }

    /**
     * Creates a Cycles reservation for the upcoming chat invocation.
     *
     * @return the reservation id when the call should proceed, or null when fail-open
     *         is engaged and no reservation was created.
     * @throws CyclesBudgetDeniedException when the Cycles server denies the call.
     * @throws IllegalStateException on transport/HTTP failure when fail-open=false.
     */
    String reserveOrFailOpen() {
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

        // Defensive: a 2xx with unrecognized decision or missing reservation_id must NOT
        // silently bypass the budget gate. Treat as malformed HTTP failure.
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

    /**
     * Commit a reservation with actual amount derived from the chat response usage.
     *
     * @param reservationId the reservation to commit.
     * @param chatResponse  the chat response (may be null when invoked from streaming
     *                      paths that didn't observe any emitted element).
     */
    void commitOrFailOpen(String reservationId, ChatClientResponse chatResponse) {
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

    /**
     * Release a reservation, swallowing any failure (transport or HTTP). The reservation
     * will TTL-expire on the server anyway, so a failed release is a logging concern,
     * not a runtime one.
     */
    void releaseQuietly(String reservationId, String reason) {
        ReleaseRequest release = ReleaseRequest.builder()
                .idempotencyKey(UUID.randomUUID().toString())
                .reason(reason)
                .build();
        try {
            CyclesResponse<Map<String, Object>> response = cyclesClient.releaseReservation(reservationId, release);
            if (!response.is2xx()) {
                log.warn("Cycles release HTTP failure for reservation {} status={} body={}",
                        reservationId, response.getStatus(), response.getBody());
            }
        } catch (RuntimeException releaseFailure) {
            log.warn("Cycles release transport failure for reservation {}: {}",
                    reservationId, releaseFailure.getMessage());
        }
    }

    // ── Helpers shared between reservation build and actual-amount build ───────────────

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
        Unit unit = resolveUnit();
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
     * <p>Tolerates null at every step — providers occasionally omit usage data in
     * non-OpenAI-shaped responses, and we don't want to throw at commit time.
     */
    private Amount buildActualAmount(ChatClientResponse chatResponse) {
        Unit unit = resolveUnit();
        Usage usage = extractUsage(chatResponse);

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

        return buildEstimateAmount();
    }

    private Unit resolveUnit() {
        Unit unit = Unit.fromString(springAiProperties.getEstimateUnit());
        return unit == null ? Unit.USD_MICROCENTS : unit;
    }

    private static Usage extractUsage(ChatClientResponse chatResponse) {
        if (chatResponse == null) {
            return null;
        }
        ChatResponse innerResponse = chatResponse.chatResponse();
        if (innerResponse == null) {
            return null;
        }
        ChatResponseMetadata metadata = innerResponse.getMetadata();
        if (metadata == null) {
            return null;
        }
        return metadata.getUsage();
    }

    private static long nullSafeLong(Integer value) {
        return value == null ? 0L : value.longValue();
    }
}
