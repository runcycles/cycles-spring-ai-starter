package io.runcycles.client.java.springai.advisor;

import io.runcycles.client.java.spring.client.CyclesClient;
import io.runcycles.client.java.spring.config.CyclesProperties;
import io.runcycles.client.java.spring.model.CommitRequest;
import io.runcycles.client.java.spring.model.CyclesResponse;
import io.runcycles.client.java.spring.model.ReleaseRequest;
import io.runcycles.client.java.spring.model.ReservationCreateRequest;
import io.runcycles.client.java.spring.model.Subject;
import io.runcycles.client.java.spring.retry.CommitRetryEngine;
import io.runcycles.client.java.springai.autoconfigure.CyclesSpringAiProperties;
import io.runcycles.client.java.springai.subject.PropertiesSubjectResolver;
import io.runcycles.client.java.springai.subject.SubjectResolver;
import io.runcycles.client.java.springai.tokenizer.CharsPerTokenEstimator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the durable commit path in {@link CyclesBudgetLifecycle} —
 * classification of commit failures into retry-engine scheduling, event fallback on
 * expiry, warn-only terminal codes, and the residual fail-open/fail-closed policy for
 * genuine 4xx rejections. Mirrors the fleet template shipped in
 * {@code cycles-client-java-spring} 0.3.0's {@code CyclesLifecycleService}.
 *
 * <p>The engine is mocked throughout: these tests pin WHAT the lifecycle hands to the
 * engine, not how the engine retries (that behavior lives in — and is tested by — the
 * {@code cycles-client-java-spring} library). No real engine means no background
 * executor and no on-disk journal touching the developer's home directory.
 */
@ExtendWith(MockitoExtension.class)
class CyclesBudgetLifecycleDurableCommitTest {

    @Mock CyclesClient cyclesClient;
    @Mock CommitRetryEngine retryEngine;

    private CyclesProperties cyclesProperties;
    private CyclesSpringAiProperties springAiProperties;
    private CyclesBudgetLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        cyclesProperties = new CyclesProperties();
        cyclesProperties.setTenant("acme");
        cyclesProperties.setWorkspace("dev");
        cyclesProperties.setApp("spring-ai");

        springAiProperties = new CyclesSpringAiProperties(); // fail-open=false default

        lifecycle = new CyclesBudgetLifecycle(cyclesClient, cyclesProperties, springAiProperties,
                new PropertiesSubjectResolver(cyclesProperties), new CharsPerTokenEstimator(),
                retryEngine);
    }

    // ---- Success ---------------------------------------------------------

    @Test
    void commit2xxDoesNotEngageRetryEngine() {
        CyclesBudgetLifecycle.ActiveReservation reservation = reserve("res-ok");
        when(cyclesClient.commitReservation(eq("res-ok"), any(CommitRequest.class)))
                .thenReturn(CyclesResponse.success(200, Map.of()));

        lifecycle.commitOrFailOpen(reservation, null);

        verifyNoInteractions(retryEngine);
    }

    // ---- Transient failures → engine.schedule ----------------------------

    @Test
    void commitTransportErrorResponseSchedulesWithFallback() {
        // DefaultCyclesClient surfaces transport failures as transportError responses
        // (status -1) rather than throwing. Same classification as a thrown exception.
        CyclesBudgetLifecycle.ActiveReservation reservation = reserve("res-transport");
        when(cyclesClient.commitReservation(eq("res-transport"), any(CommitRequest.class)))
                .thenReturn(CyclesResponse.transportError(new RuntimeException("connection reset")));

        assertThatCode(() -> lifecycle.commitOrFailOpen(reservation, null))
                .doesNotThrowAnyException();

        verify(retryEngine).schedule(eq("res-transport"), anyMap(), anyMap(), isNull());
    }

    @Test
    void commitThrownExceptionSchedulesWithFallbackEvenWhenFailClosed() {
        CyclesBudgetLifecycle.ActiveReservation reservation = reserve("res-thrown");
        when(cyclesClient.commitReservation(eq("res-thrown"), any(CommitRequest.class)))
                .thenThrow(new RuntimeException("connection refused"));

        assertThatCode(() -> lifecycle.commitOrFailOpen(reservation, null))
                .doesNotThrowAnyException();

        verify(retryEngine).schedule(eq("res-thrown"), anyMap(), anyMap(), isNull());
    }

    @Test
    void commit429SchedulesWithServerRetryAfter() {
        // Rate-limited: the server's Retry-After (converted to ms by the client) is
        // passed to the engine so the first background retry honors the server delay.
        CyclesBudgetLifecycle.ActiveReservation reservation = reserve("res-429");
        when(cyclesClient.commitReservation(eq("res-429"), any(CommitRequest.class)))
                .thenReturn(CyclesResponse.httpError(429, "rate limited",
                        Map.of("error", "LIMIT_EXCEEDED"), 1500));

        lifecycle.commitOrFailOpen(reservation, null);

        verify(retryEngine).schedule(eq("res-429"), anyMap(), anyMap(), eq(1500));
    }

    @Test
    void bodyless429SchedulesWithNullRetryAfter() {
        // Some proxies emit bodyless 429s with no Retry-After. Still transient — must
        // schedule (with null retryAfterMs), never throw, never drop.
        CyclesBudgetLifecycle.ActiveReservation reservation = reserve("res-429-bare");
        when(cyclesClient.commitReservation(eq("res-429-bare"), any(CommitRequest.class)))
                .thenReturn(CyclesResponse.httpError(429, "rate limited", null));

        assertThatCode(() -> lifecycle.commitOrFailOpen(reservation, null))
                .doesNotThrowAnyException();

        verify(retryEngine).schedule(eq("res-429-bare"), anyMap(), anyMap(), isNull());
    }

    @Test
    void retryableUnrecognizedErrorCodeOn4xxSchedules() {
        // A 4xx carrying an error code this client version doesn't know maps to
        // ErrorCode.UNKNOWN, which is classified retryable — schedule rather than
        // treat as a genuine rejection (matches the library's fleet template).
        CyclesBudgetLifecycle.ActiveReservation reservation = reserve("res-unknown");
        when(cyclesClient.commitReservation(eq("res-unknown"), any(CommitRequest.class)))
                .thenReturn(CyclesResponse.httpError(400, "future error",
                        Map.of("error", "SOME_FUTURE_CODE")));

        lifecycle.commitOrFailOpen(reservation, null);

        verify(retryEngine).schedule(eq("res-unknown"), anyMap(), anyMap(), isNull());
    }

    @Test
    void commitBodyHandedToEngineMatchesWireCommit() {
        // The body scheduled for retry must be the SAME commit (same idempotency key,
        // same actual) that failed on the wire — a replay is a retry, not a new spend.
        CyclesBudgetLifecycle.ActiveReservation reservation = reserve("res-body");
        ArgumentCaptor<CommitRequest> wireCommit = ArgumentCaptor.forClass(CommitRequest.class);
        when(cyclesClient.commitReservation(eq("res-body"), wireCommit.capture()))
                .thenReturn(CyclesResponse.httpError(503, "unavailable", Map.of()));

        lifecycle.commitOrFailOpen(reservation, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> commitBody = ArgumentCaptor.forClass(Map.class);
        verify(retryEngine).schedule(eq("res-body"), commitBody.capture(), anyMap(), isNull());
        assertThat(commitBody.getValue())
                .containsEntry("idempotency_key", wireCommit.getValue().getIdempotencyKey())
                .containsEntry("actual", wireCommit.getValue().getActual().toMap());
    }

    // ---- 401/403 → schedule for replay, never throw, never release --------

    @Test
    void commit401SchedulesForReplayAndNeverThrows() {
        CyclesBudgetLifecycle.ActiveReservation reservation = reserve("res-401");
        when(cyclesClient.commitReservation(eq("res-401"), any(CommitRequest.class)))
                .thenReturn(CyclesResponse.httpError(401, "unauthorized",
                        Map.of("error", "UNAUTHORIZED")));

        assertThatCode(() -> lifecycle.commitOrFailOpen(reservation, null))
                .doesNotThrowAnyException();

        verify(retryEngine).schedule(eq("res-401"), anyMap(), anyMap(), isNull());
        verify(cyclesClient, never()).releaseReservation(anyString(), any(ReleaseRequest.class));
    }

    @Test
    void commit403SchedulesForReplayAndNeverThrows() {
        CyclesBudgetLifecycle.ActiveReservation reservation = reserve("res-403");
        when(cyclesClient.commitReservation(eq("res-403"), any(CommitRequest.class)))
                .thenReturn(CyclesResponse.httpError(403, "forbidden",
                        Map.of("error", "FORBIDDEN")));

        assertThatCode(() -> lifecycle.commitOrFailOpen(reservation, null))
                .doesNotThrowAnyException();

        verify(retryEngine).schedule(eq("res-403"), anyMap(), anyMap(), isNull());
        verify(cyclesClient, never()).releaseReservation(anyString(), any(ReleaseRequest.class));
    }

    // ---- RESERVATION_EXPIRED → event fallback ----------------------------

    @Test
    void reservationExpiredSchedulesEventWithRecoveryMetadata() {
        // The server already returned the reserved budget to the pool; a commit retry
        // can never land. The spend is recovered via POST /v1/events instead.
        CyclesBudgetLifecycle.ActiveReservation reservation = reserve("res-expired");
        ArgumentCaptor<CommitRequest> wireCommit = ArgumentCaptor.forClass(CommitRequest.class);
        when(cyclesClient.commitReservation(eq("res-expired"), wireCommit.capture()))
                .thenReturn(CyclesResponse.httpError(410, "gone",
                        Map.of("error", "RESERVATION_EXPIRED")));

        lifecycle.commitOrFailOpen(reservation, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> eventBody = ArgumentCaptor.forClass(Map.class);
        verify(retryEngine).scheduleEvent(eq("res-expired"), eventBody.capture());
        verify(retryEngine, never()).schedule(anyString(), anyMap(), anyMap(), any());

        Map<String, Object> body = eventBody.getValue();
        // Idempotency key reuse: the event replays exactly-once across restarts.
        assertThat(body).containsEntry("idempotency_key", wireCommit.getValue().getIdempotencyKey());
        // Subject/action threaded from reservation time to the commit site.
        Map<String, Object> createBody = reservation.request().toMap();
        assertThat(body).containsEntry("subject", createBody.get("subject"));
        assertThat(body).containsEntry("action", createBody.get("action"));
        assertThat(body).containsEntry("actual", wireCommit.getValue().getActual().toMap());
        // Recovery markers; no overage_policy (spec default ALLOW_IF_AVAILABLE never
        // rejects — the right bias for spend that already happened).
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) body.get("metadata");
        assertThat(metadata)
                .containsEntry("recovered_reservation_id", "res-expired")
                .containsEntry("recovery_reason", "commit_after_reservation_expired");
        assertThat(body).doesNotContainKey("overage_policy");
        // No metrics configured on the starter's commits — key must be absent, not null.
        assertThat(body).doesNotContainKey("metrics");
    }

    @Test
    void eventFallbackCarriesCustomResolverSubject() {
        // A custom SubjectResolver's attribution must survive into the event fallback —
        // the recovered spend has to land on the same subject the reservation used.
        SubjectResolver custom = req -> Subject.builder()
                .tenant("dynamic-tenant")
                .app("dynamic-app")
                .build();
        lifecycle = new CyclesBudgetLifecycle(cyclesClient, cyclesProperties, springAiProperties,
                custom, new CharsPerTokenEstimator(), retryEngine);

        CyclesBudgetLifecycle.ActiveReservation reservation = reserve("res-custom-subject");
        when(cyclesClient.commitReservation(eq("res-custom-subject"), any(CommitRequest.class)))
                .thenReturn(CyclesResponse.httpError(410, "gone",
                        Map.of("error", "RESERVATION_EXPIRED")));

        lifecycle.commitOrFailOpen(reservation, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> eventBody = ArgumentCaptor.forClass(Map.class);
        verify(retryEngine).scheduleEvent(eq("res-custom-subject"), eventBody.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> subject = (Map<String, Object>) eventBody.getValue().get("subject");
        assertThat(subject)
                .containsEntry("tenant", "dynamic-tenant")
                .containsEntry("app", "dynamic-app");
    }

    // ---- Terminal warn-only codes ----------------------------------------

    @Test
    void reservationFinalizedNeitherSchedulesNorThrows() {
        CyclesBudgetLifecycle.ActiveReservation reservation = reserve("res-finalized");
        when(cyclesClient.commitReservation(eq("res-finalized"), any(CommitRequest.class)))
                .thenReturn(CyclesResponse.httpError(409, "conflict",
                        Map.of("error", "RESERVATION_FINALIZED")));

        assertThatCode(() -> lifecycle.commitOrFailOpen(reservation, null))
                .doesNotThrowAnyException();

        verifyNoInteractions(retryEngine);
        verify(cyclesClient, never()).releaseReservation(anyString(), any(ReleaseRequest.class));
    }

    @Test
    void idempotencyMismatchNeitherSchedulesNorThrows() {
        CyclesBudgetLifecycle.ActiveReservation reservation = reserve("res-idem");
        when(cyclesClient.commitReservation(eq("res-idem"), any(CommitRequest.class)))
                .thenReturn(CyclesResponse.httpError(409, "conflict",
                        Map.of("error", "IDEMPOTENCY_MISMATCH")));

        assertThatCode(() -> lifecycle.commitOrFailOpen(reservation, null))
                .doesNotThrowAnyException();

        verifyNoInteractions(retryEngine);
    }

    // ---- Genuine 4xx rejections keep the old fail-open/fail-closed policy --

    @Test
    void genuineRejectionThrowsWhenFailClosed() {
        CyclesBudgetLifecycle.ActiveReservation reservation = reserve("res-rejected");
        when(cyclesClient.commitReservation(eq("res-rejected"), any(CommitRequest.class)))
                .thenReturn(CyclesResponse.httpError(422, "unit mismatch",
                        Map.of("error", "UNIT_MISMATCH")));

        assertThatThrownBy(() -> lifecycle.commitOrFailOpen(reservation, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("commit HTTP failure")
                .hasMessageContaining("res-rejected");

        verifyNoInteractions(retryEngine);
    }

    @Test
    void genuineRejectionLoggedWhenFailOpen() {
        springAiProperties.setFailOpen(true);
        CyclesBudgetLifecycle.ActiveReservation reservation = reserve("res-rejected-open");
        when(cyclesClient.commitReservation(eq("res-rejected-open"), any(CommitRequest.class)))
                .thenReturn(CyclesResponse.httpError(422, "unit mismatch",
                        Map.of("error", "UNIT_MISMATCH")));

        assertThatCode(() -> lifecycle.commitOrFailOpen(reservation, null))
                .doesNotThrowAnyException();

        verifyNoInteractions(retryEngine);
    }

    @Test
    void bodyless4xxWithNoErrorCodeTreatedAsGenuineRejection() {
        // A 404 with no error envelope (errorCode=null) is not classifiable as
        // transient — old policy applies (fail-closed: throw).
        CyclesBudgetLifecycle.ActiveReservation reservation = reserve("res-404");
        when(cyclesClient.commitReservation(eq("res-404"), any(CommitRequest.class)))
                .thenReturn(CyclesResponse.httpError(404, "not found", null));

        assertThatThrownBy(() -> lifecycle.commitOrFailOpen(reservation, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("commit HTTP failure");

        verifyNoInteractions(retryEngine);
    }

    // ---- Helpers ---------------------------------------------------------

    /** Reserve through the real lifecycle so the ActiveReservation carries the actual request. */
    private CyclesBudgetLifecycle.ActiveReservation reserve(String reservationId) {
        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenReturn(CyclesResponse.success(200, Map.of(
                        "decision", "ALLOW",
                        "reservation_id", reservationId,
                        "scope_path", "tenant/acme"
                )));
        CyclesBudgetLifecycle.ActiveReservation reservation = lifecycle.reserveOrFailOpen(null);
        assertThat(reservation).isNotNull();
        assertThat(reservation.id()).isEqualTo(reservationId);
        return reservation;
    }
}
