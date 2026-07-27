package io.runcycles.client.java.springai.advisor;

import io.runcycles.client.java.spring.client.CyclesClient;
import io.runcycles.client.java.spring.config.CyclesProperties;
import io.runcycles.client.java.spring.model.Action;
import io.runcycles.client.java.spring.model.Amount;
import io.runcycles.client.java.spring.model.CommitRequest;
import io.runcycles.client.java.spring.model.CyclesResponse;
import io.runcycles.client.java.spring.model.ErrorCode;
import io.runcycles.client.java.spring.model.ErrorResponse;
import io.runcycles.client.java.spring.model.ReleaseRequest;
import io.runcycles.client.java.spring.model.ReservationCreateRequest;
import io.runcycles.client.java.spring.model.ReservationResult;
import io.runcycles.client.java.spring.model.Unit;
import io.runcycles.client.java.spring.retry.CommitRetryEngine;
import io.runcycles.client.java.spring.retry.JournaledCommitRetryEngine;
import io.runcycles.client.java.springai.CyclesBudgetDeniedException;
import io.runcycles.client.java.springai.autoconfigure.CyclesSpringAiProperties;
import io.runcycles.client.java.springai.subject.PropertiesSubjectResolver;
import io.runcycles.client.java.springai.subject.SubjectResolver;
import io.runcycles.client.java.springai.tokenizer.CharsPerTokenEstimator;
import io.runcycles.client.java.springai.tokenizer.PromptTokenEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Reserve / commit / release lifecycle shared by the chat advisors and the tool callback
 * wrapper.
 *
 * <p>Used by {@link CyclesBudgetAdvisor} (non-streaming chat),
 * {@link CyclesBudgetStreamAdvisor} (streaming chat), and
 * {@code CyclesToolCallback} (per-tool gating). Centralizes the reservation-against-Cycles
 * plumbing — wire calls, fail-open handling, actual-amount extraction from
 * {@code ChatResponse.Usage}, and durable commit handling via a
 * {@link CommitRetryEngine}.
 *
 * <p><strong>Commit durability.</strong> A commit records spend that has ALREADY
 * happened, so a failed commit must never be silently dropped (that under-counts real
 * spend) nor released. Transient commit failures (transport, 5xx, 429 rate limiting,
 * retryable error codes) and credential failures (401/403) are handed to the
 * {@link CommitRetryEngine} for journaled background retry — in BOTH fail-open and
 * fail-closed modes; durability owns those failures now, replacing the old
 * fail-open silent-drop / fail-closed throw. When the reservation already expired
 * server-side ({@code RESERVATION_EXPIRED} — or a raw 410 status with an
 * unparseable body — the server has returned the reserved budget to the pool), the
 * spend is recovered via the engine's {@code POST /v1/events} fallback. Only genuine
 * 4xx rejections keep the historical fail-open/fail-closed policy (fail-open: log;
 * fail-closed: throw) — and the lifecycle releases the reservation itself
 * (best-effort, reason {@code commit_rejected_<code>}) before applying that policy,
 * so every commit site (call advisor, stream advisor, tool callback) observes ONE
 * consistent behavior. Unclassifiable non-4xx statuses (e.g. a 3xx from a proxy)
 * are warn-only, matching the library.
 *
 * <p><strong>Internal API.</strong> Public for cross-package access only. Not part of the
 * stable user-facing surface — methods may change between minor releases. The stable
 * surface is the advisor classes, the {@code CyclesToolCallback}/{@code CyclesToolGate}
 * factory, and the {@link CyclesSpringAiProperties} configuration block.
 */
public final class CyclesBudgetLifecycle {

    private static final Logger log = LoggerFactory.getLogger(CyclesBudgetLifecycle.class);

    private final CyclesClient cyclesClient;
    private final CyclesProperties cyclesProperties;
    private final CyclesSpringAiProperties springAiProperties;
    private final SubjectResolver subjectResolver;
    private final PromptTokenEstimator tokenEstimator;
    private final CommitRetryEngine retryEngine;

    /**
     * An active (allowed) reservation plus the request it was created from. The
     * originating request is threaded to the commit site so the event-fallback body
     * (used when the reservation expires before the commit lands) can carry the same
     * subject and action the reservation was attributed to.
     *
     * @param id      the server-assigned reservation id (never null/blank).
     * @param request the reservation-create request this reservation was made from.
     */
    public record ActiveReservation(String id, ReservationCreateRequest request) { }

    /**
     * Constructs the lifecycle helper with explicit subject, token-estimator, and
     * commit-retry strategies. Preferred constructor — wired by the auto-configuration
     * with the Spring-managed {@link CommitRetryEngine} bean (whose
     * {@code DisposableBean} flush then runs on context shutdown). Public for
     * cross-package access; not for direct user instantiation (use the advisor/tool
     * classes that wrap it).
     *
     * @param cyclesClient       Cycles HTTP client.
     * @param cyclesProperties   SDK-level configuration.
     * @param springAiProperties Spring AI integration configuration.
     * @param subjectResolver    resolves the Cycles subject for each reservation.
     * @param tokenEstimator     estimates the input-side token count for prompt-based
     *                           reservation sizing.
     * @param retryEngine        durable retry engine that owns failed commits.
     */
    public CyclesBudgetLifecycle(CyclesClient cyclesClient,
                                 CyclesProperties cyclesProperties,
                                 CyclesSpringAiProperties springAiProperties,
                                 SubjectResolver subjectResolver,
                                 PromptTokenEstimator tokenEstimator,
                                 CommitRetryEngine retryEngine) {
        this.cyclesClient = cyclesClient;
        this.cyclesProperties = cyclesProperties;
        this.springAiProperties = springAiProperties;
        this.subjectResolver = subjectResolver;
        this.tokenEstimator = tokenEstimator;
        this.retryEngine = retryEngine;
    }

    /**
     * Backward-compatible constructor — creates a private
     * {@link JournaledCommitRetryEngine} so direct-instantiation callers still get
     * durable commit handling. Prefer the engine-injecting constructor: a
     * Spring-managed engine bean is shared across the whole app and flushed on
     * context shutdown, while this private engine is per-lifecycle and relies on its
     * on-disk journal (replayed on next run) for anything in flight at JVM exit.
     *
     * @param cyclesClient       Cycles HTTP client.
     * @param cyclesProperties   SDK-level configuration.
     * @param springAiProperties Spring AI integration configuration.
     * @param subjectResolver    resolves the Cycles subject for each reservation.
     * @param tokenEstimator     estimates the input-side token count for prompt-based
     *                           reservation sizing.
     * @deprecated <strong>Constructs a LIVE {@link JournaledCommitRetryEngine} per
     *             instance</strong>: with production-shaped {@code CyclesProperties}
     *             (base-url + api-key/tenant set) it writes an on-disk journal under
     *             the real user home ({@code ~/.runcycles}) and performs a
     *             once-per-JVM startup replay of pending records. In unit tests a
     *             mocked client could replay — and discard — real pending commit
     *             records. Tests (and Spring apps) should use the engine-injecting
     *             constructor with a mock/managed {@link CommitRetryEngine} instead.
     */
    @Deprecated
    public CyclesBudgetLifecycle(CyclesClient cyclesClient,
                                 CyclesProperties cyclesProperties,
                                 CyclesSpringAiProperties springAiProperties,
                                 SubjectResolver subjectResolver,
                                 PromptTokenEstimator tokenEstimator) {
        this(cyclesClient, cyclesProperties, springAiProperties, subjectResolver,
                tokenEstimator, new JournaledCommitRetryEngine(cyclesClient, cyclesProperties));
    }

    /**
     * Backward-compatible constructor — uses {@link CharsPerTokenEstimator} as the
     * default token estimator alongside the supplied subject resolver.
     *
     * @param cyclesClient       Cycles HTTP client.
     * @param cyclesProperties   SDK-level configuration.
     * @param springAiProperties Spring AI integration configuration.
     * @param subjectResolver    resolves the Cycles subject for each reservation.
     * @deprecated <strong>Constructs a LIVE {@link JournaledCommitRetryEngine} per
     *             instance</strong> (writes to {@code ~/.runcycles}, performs a
     *             once-per-JVM startup replay with production-shaped properties).
     *             Tests should use the engine-injecting constructor with a mock.
     */
    @Deprecated
    public CyclesBudgetLifecycle(CyclesClient cyclesClient,
                                 CyclesProperties cyclesProperties,
                                 CyclesSpringAiProperties springAiProperties,
                                 SubjectResolver subjectResolver) {
        this(cyclesClient, cyclesProperties, springAiProperties,
                subjectResolver, new CharsPerTokenEstimator());
    }

    /**
     * Backward-compatible constructor — uses {@link PropertiesSubjectResolver} as the
     * default subject resolver and {@link CharsPerTokenEstimator} as the default token
     * estimator. Equivalent to v0.1.0 / v0.2.0 behavior.
     *
     * @param cyclesClient       Cycles HTTP client.
     * @param cyclesProperties   SDK-level configuration (also feeds the default resolver).
     * @param springAiProperties Spring AI integration configuration.
     * @deprecated <strong>Constructs a LIVE {@link JournaledCommitRetryEngine} per
     *             instance</strong> (writes to {@code ~/.runcycles}, performs a
     *             once-per-JVM startup replay with production-shaped properties).
     *             Tests should use the engine-injecting constructor with a mock.
     */
    @Deprecated
    public CyclesBudgetLifecycle(CyclesClient cyclesClient,
                                 CyclesProperties cyclesProperties,
                                 CyclesSpringAiProperties springAiProperties) {
        this(cyclesClient, cyclesProperties, springAiProperties,
                new PropertiesSubjectResolver(cyclesProperties), new CharsPerTokenEstimator());
    }

    /**
     * Chat-flavored reservation — uses the configured chat action labels
     * ({@code cycles.spring-ai.action-kind} / {@code action-name}).
     *
     * @param request the originating ChatClientRequest. Used for prompt-based estimation
     *                when {@code estimate-from-prompt=true}. May be null.
     * @return the active reservation or null on fail-open skip.
     */
    public ActiveReservation reserveOrFailOpen(ChatClientRequest request) {
        return reserveOrFailOpen(request,
                springAiProperties.getActionKind(),
                springAiProperties.getActionName());
    }

    /**
     * Reservation with explicit action labels — used by the tool-callback wrapper to
     * distinguish tool invocations from chat invocations in Cycles audit history.
     *
     * @param request    originating request for prompt-based estimation, or null.
     * @param actionKind action kind label to report (e.g. {@code llm.chat}, {@code tool.call}).
     * @param actionName action name label to report (e.g. tool name).
     * @return the active reservation or null on fail-open skip.
     */
    public ActiveReservation reserveOrFailOpen(ChatClientRequest request, String actionKind, String actionName) {
        ReservationCreateRequest req = buildReservationRequest(request, actionKind, actionName);
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
        return new ActiveReservation(reservationId, req);
    }

    private ActiveReservation handleReserveTransportFailure(RuntimeException cause) {
        if (springAiProperties.isFailOpen()) {
            log.warn("Cycles reservation transport failure (fail-open=true; proceeding without budget gate): {}",
                    cause.getMessage());
            return null;
        }
        throw new IllegalStateException("Cycles reservation failed (fail-open=false)", cause);
    }

    private ActiveReservation handleReserveHttpFailure(CyclesResponse<Map<String, Object>> response) {
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
     * <p>The spend being committed has ALREADY happened, so this method never drops a
     * commit silently. Outcome classification (mirrors the fleet template in
     * {@code cycles-client-java-spring}'s {@code CyclesLifecycleService}):
     * <ul>
     *   <li>2xx — success.</li>
     *   <li>Transport failure / 5xx / 429 (including bodyless) / retryable error code —
     *       handed to the {@link CommitRetryEngine} (with the server's
     *       {@code Retry-After} on 429). Never thrown, never counted as failure —
     *       durability owns it now, in BOTH fail-open and fail-closed modes.</li>
     *   <li>401/403 — error-logged and scheduled for replay (fix credentials, restart).
     *       Never released, never thrown.</li>
     *   <li>{@code RESERVATION_EXPIRED} — or a raw 410 status whose body carries no
     *       parseable error code (bodyless/mangled 410) — spend recovered via the
     *       engine's {@code POST /v1/events} fallback.</li>
     *   <li>{@code RESERVATION_FINALIZED} / {@code IDEMPOTENCY_MISMATCH} — warn only.</li>
     *   <li>Other genuine 4xx rejections — the reservation is first released
     *       best-effort with reason {@code commit_rejected_<code>} (the server
     *       rejected the commit, so the held budget must be returned), THEN the
     *       historical fail-open/fail-closed policy applies (fail-open: log;
     *       fail-closed: {@link IllegalStateException}). Because the release happens
     *       here, callers must NOT release again when the fail-closed exception
     *       propagates.</li>
     *   <li>Any other non-2xx status (e.g. a 3xx from a proxy) — unclassifiable;
     *       warn only, no throw, no schedule (matches the library).</li>
     * </ul>
     *
     * @param reservation  the active reservation to commit (carries the originating
     *                     subject/action for the event fallback body).
     * @param chatResponse the chat response (may be null when invoked from streaming
     *                     paths that didn't observe any emitted element).
     */
    public void commitOrFailOpen(ActiveReservation reservation, ChatClientResponse chatResponse) {
        String reservationId = reservation.id();
        CommitRequest commit = CommitRequest.builder()
                .idempotencyKey(UUID.randomUUID().toString())
                .actual(buildActualAmount(chatResponse))
                .build();
        Map<String, Object> commitBody = commit.toMap();
        Map<String, Object> eventFallbackBody =
                buildEventFallbackBody(reservationId, reservation.request().toMap(), commitBody);

        CyclesResponse<Map<String, Object>> commitResponse;
        try {
            commitResponse = cyclesClient.commitReservation(reservationId, commit);
        } catch (RuntimeException transportFailure) {
            // Spend already happened — schedule the commit for durable background
            // retry instead of dropping (fail-open) or failing the call (fail-closed).
            log.warn("Cycles commit transport failure for reservation {}; scheduling durable retry: {}",
                    reservationId, transportFailure.getMessage());
            retryEngine.schedule(reservationId, commitBody, eventFallbackBody, null);
            return;
        }
        if (commitResponse.is2xx()) {
            return;
        }

        ErrorCode errorCode = extractErrorCode(commitResponse);
        if (commitResponse.isTransportError() || commitResponse.is5xx()
                || commitResponse.getStatus() == 429
                || (errorCode != null && errorCode.isRetryable())) {
            // Transient (transport, 5xx, or 429/LIMIT_EXCEEDED rate limiting — including
            // bodyless 429s): not a rejection. Retry durably, honoring Retry-After.
            Integer retryAfterMs = commitResponse.getStatus() == 429
                    ? commitResponse.getRetryAfterMs() : null;
            log.warn("Cycles commit transient failure status={} for reservation {}; "
                            + "scheduling durable retry (retryAfterMs={}): body={}",
                    commitResponse.getStatus(), reservationId, retryAfterMs, commitResponse.getBody());
            retryEngine.schedule(reservationId, commitBody, eventFallbackBody, retryAfterMs);
            return;
        }
        if (commitResponse.getStatus() == 401 || commitResponse.getStatus() == 403) {
            // Credentials failed after the spend happened: journal for replay once
            // they're fixed. Never release, never throw — that would lose real spend.
            log.error("Cycles commit got authentication failure (status={}) for reservation {}; "
                            + "scheduling for replay — fix credentials and restart",
                    commitResponse.getStatus(), reservationId);
            retryEngine.schedule(reservationId, commitBody, eventFallbackBody, null);
            return;
        }
        if (errorCode == ErrorCode.RESERVATION_EXPIRED || commitResponse.getStatus() == 410) {
            // The server already returned the reserved budget to the pool; the spend
            // still has to be recorded — recover via POST /v1/events. The raw 410
            // status also triggers this (matching the SDK): a bodyless or mangled 410
            // is still an expired reservation, not a genuine rejection.
            log.warn("Cycles reservation expired before commit; recovering spend via POST /v1/events: "
                    + "reservationId={}", reservationId);
            retryEngine.scheduleEvent(reservationId, eventFallbackBody);
            return;
        }
        if (errorCode == ErrorCode.RESERVATION_FINALIZED) {
            log.warn("Cycles reservation already finalized; commit skipped: reservationId={}",
                    reservationId);
            return;
        }
        if (errorCode == ErrorCode.IDEMPOTENCY_MISMATCH) {
            log.warn("Cycles commit idempotency mismatch; commit skipped: reservationId={}",
                    reservationId);
            return;
        }
        if (!commitResponse.is4xx()) {
            // Unclassifiable non-2xx status outside every known class (e.g. a 3xx
            // from a misconfigured proxy). Matches the library's fleet template:
            // warn only — no throw, no schedule, no release.
            log.warn("Cycles commit got unclassifiable status={} for reservation {} "
                            + "(warn only): body={}",
                    commitResponse.getStatus(), reservationId, commitResponse.getBody());
            return;
        }
        // Genuine 4xx rejection (e.g. INVALID_REQUEST, UNIT_MISMATCH) — the only
        // remaining place where the historical fail-open/fail-closed policy applies.
        // The server rejected the commit, so the reservation is still held: release
        // it best-effort FIRST (matching the library's commit_rejected_<code>
        // release) so every commit site — call advisor, stream advisor, tool
        // callback — gets consistent behavior and never re-releases on the
        // fail-closed throw below.
        releaseQuietly(reservationId, "commit_rejected_" + errorCode);
        if (springAiProperties.isFailOpen()) {
            log.warn("Cycles commit rejected status={} error={} for reservation {} "
                            + "(fail-open=true; ignoring): body={}",
                    commitResponse.getStatus(), errorCode, reservationId, commitResponse.getBody());
            return;
        }
        throw new IllegalStateException(
                "Cycles commit HTTP failure status=" + commitResponse.getStatus()
                        + " for reservation " + reservationId);
    }

    /**
     * Builds the {@code POST /v1/events} body that records the spend of a commit whose
     * reservation expired before the commit landed (the server has already returned the
     * reserved budget to the pool at that point).
     *
     * <p>Reuses the commit's idempotency key — the event idempotency namespace is
     * separate, so journal replays across JVM restarts stay exactly-once. Carries the
     * subject/action the reservation was attributed to, plus recovery markers in
     * metadata. Deliberately omits {@code overage_policy}: the spec default
     * ALLOW_IF_AVAILABLE never rejects, which is the right bias when the spend has
     * already happened.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> buildEventFallbackBody(String reservationId,
                                                              Map<String, Object> createBody,
                                                              Map<String, Object> commitBody) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (commitBody.get("metadata") instanceof Map<?, ?> existing) {
            metadata.putAll((Map<String, Object>) existing);
        }
        metadata.put("recovered_reservation_id", reservationId);
        metadata.put("recovery_reason", "commit_after_reservation_expired");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("idempotency_key", commitBody.get("idempotency_key"));
        body.put("subject", createBody.get("subject"));
        body.put("action", createBody.get("action"));
        body.put("actual", commitBody.get("actual"));
        body.put("metadata", metadata);
        if (commitBody.containsKey("metrics")) {
            body.put("metrics", commitBody.get("metrics"));
        }
        return body;
    }

    /**
     * Extracts the structured error code from a commit response, tolerating bodies
     * that don't carry the full error envelope (falls back to the raw {@code error}
     * attribute, and to null for bodyless responses).
     */
    private static ErrorCode extractErrorCode(CyclesResponse<Map<String, Object>> response) {
        ErrorResponse errorResponse = response.getErrorResponse();
        if (errorResponse != null && errorResponse.getErrorCode() != null) {
            return errorResponse.getErrorCode();
        }
        return ErrorCode.fromString(response.getBodyAttributeAsString("error"));
    }

    /**
     * Release a reservation, swallowing any failure (transport or HTTP). The reservation
     * will TTL-expire on the server anyway, so a failed release is a logging concern,
     * not a runtime one.
     *
     * @param reservationId the reservation to release.
     * @param reason        free-form reason string captured in audit history.
     */
    public void releaseQuietly(String reservationId, String reason) {
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

    private ReservationCreateRequest buildReservationRequest(ChatClientRequest request,
                                                              String actionKind,
                                                              String actionName) {
        return ReservationCreateRequest.builder()
                .idempotencyKey(UUID.randomUUID().toString())
                .subject(subjectResolver.resolveSubject(request))
                .action(new Action(actionKind, actionName, null))
                .estimate(buildReservationEstimate(request))
                .build();
    }

    /**
     * Compute the reservation estimate. When {@code estimate-from-prompt=true} and at
     * least one cost-per-token rate is set, asks the configured
     * {@link PromptTokenEstimator} how many tokens the prompt will use, then multiplies
     * by the sum of the input and output rates (assuming output ≈ input in token count,
     * which is conservative-ish for most chat workloads). Falls back to
     * {@link #buildEstimateAmount} (i.e. {@code default-estimate}) when prompt-based
     * estimation isn't applicable or yields zero.
     */
    private Amount buildReservationEstimate(ChatClientRequest request) {
        if (springAiProperties.isEstimateFromPrompt() && request != null) {
            long inputRate = springAiProperties.getInputCostPerToken();
            long outputRate = springAiProperties.getOutputCostPerToken();
            if (inputRate > 0 || outputRate > 0) {
                long estimatedTokens = tokenEstimator.estimateTokens(request);
                if (estimatedTokens > 0) {
                    // estimate is guaranteed > 0 here: tokens > 0 AND at least one rate
                    // > 0 (guarded above), so tokens × (inputRate + outputRate) > 0.
                    // No additional zero-guard needed.
                    long estimate = estimatedTokens * (inputRate + outputRate);
                    return new Amount(resolveUnit(), estimate);
                }
            }
        }
        return buildEstimateAmount();
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
            Integer promptTokens = usage.getPromptTokens();
            Integer completionTokens = usage.getCompletionTokens();
            // A Usage object that returns null for BOTH breakdown fields is "I have no
            // idea" (provider didn't populate the response), not "no work done". Treating
            // it as zero would silently under-bill — fall back to the estimate instead.
            // When only one breakdown is missing we still bill what we have (some
            // providers populate prompt tokens only during streaming, for example).
            if (promptTokens == null && completionTokens == null) {
                return buildEstimateAmount();
            }
            long actual = (nullSafeLong(promptTokens) * inputRate)
                        + (nullSafeLong(completionTokens) * outputRate);
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
