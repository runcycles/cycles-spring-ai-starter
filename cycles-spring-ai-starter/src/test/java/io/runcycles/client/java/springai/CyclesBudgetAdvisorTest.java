package io.runcycles.client.java.springai;

import io.runcycles.client.java.spring.client.CyclesClient;
import io.runcycles.client.java.spring.config.CyclesProperties;
import io.runcycles.client.java.spring.model.CommitRequest;
import io.runcycles.client.java.spring.model.CyclesResponse;
import io.runcycles.client.java.spring.model.ReleaseRequest;
import io.runcycles.client.java.spring.model.ReservationCreateRequest;
import io.runcycles.client.java.springai.advisor.CyclesBudgetAdvisor;
import io.runcycles.client.java.springai.autoconfigure.CyclesSpringAiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.core.Ordered;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CyclesBudgetAdvisor}. Mocks {@link CyclesClient} so the tests
 * verify Cycles wire calls without needing a running server. Covers every branch in
 * reserve / commit / release plus fail-open variants.
 */
@ExtendWith(MockitoExtension.class)
class CyclesBudgetAdvisorTest {

    @Mock CyclesClient cyclesClient;
    @Mock CallAdvisorChain chain;
    @Mock ChatClientRequest request;
    @Mock ChatClientResponse response;

    private CyclesProperties cyclesProperties;
    private CyclesSpringAiProperties springAiProperties;
    private CyclesBudgetAdvisor advisor;

    @BeforeEach
    void setUp() {
        cyclesProperties = new CyclesProperties();
        cyclesProperties.setTenant("acme");
        cyclesProperties.setWorkspace("dev");
        cyclesProperties.setApp("spring-ai");

        springAiProperties = new CyclesSpringAiProperties();

        advisor = new CyclesBudgetAdvisor(cyclesClient, cyclesProperties, springAiProperties);
    }

    // ---- Metadata --------------------------------------------------------

    @Test
    void nameIsCyclesBudget() {
        assertThat(advisor.getName()).isEqualTo("cycles-budget");
    }

    @Test
    void orderRunsEarlyEnoughToGateCost() {
        assertThat(advisor.getOrder()).isLessThan(Ordered.LOWEST_PRECEDENCE);
        assertThat(advisor.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 100);
    }

    // ---- Happy path ------------------------------------------------------

    @Test
    void reserveAllowThenCallThenCommit() {
        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenReturn(reservationAllow("res-123"));
        when(chain.nextCall(request)).thenReturn(response);
        when(cyclesClient.commitReservation(eq("res-123"), any(CommitRequest.class)))
                .thenReturn(CyclesResponse.success(200, Map.of()));

        ChatClientResponse result = advisor.adviseCall(request, chain);

        assertThat(result).isSameAs(response);
        verify(chain).nextCall(request);
        verify(cyclesClient).commitReservation(eq("res-123"), any(CommitRequest.class));
        verify(cyclesClient, never()).releaseReservation(anyString(), any(ReleaseRequest.class));
    }

    @Test
    void reservationRequestCarriesSubjectAndActionFromConfiguration() {
        ArgumentCaptor<ReservationCreateRequest> captor =
                ArgumentCaptor.forClass(ReservationCreateRequest.class);
        when(cyclesClient.createReservation(captor.capture()))
                .thenReturn(reservationAllow("res-1"));
        when(chain.nextCall(request)).thenReturn(response);
        when(cyclesClient.commitReservation(anyString(), any(CommitRequest.class)))
                .thenReturn(CyclesResponse.success(200, Map.of()));

        advisor.adviseCall(request, chain);

        ReservationCreateRequest sent = captor.getValue();
        assertThat(sent.getSubject().getTenant()).isEqualTo("acme");
        assertThat(sent.getSubject().getWorkspace()).isEqualTo("dev");
        assertThat(sent.getSubject().getApp()).isEqualTo("spring-ai");
        assertThat(sent.getAction().getKind()).isEqualTo("llm.chat");
        assertThat(sent.getAction().getName()).isEqualTo("spring-ai-chat");
        assertThat(sent.getEstimate().getAmount()).isEqualTo(1000L);
    }

    // ---- Denial ----------------------------------------------------------

    @Test
    void reserveDenyThrowsAndSkipsCall() {
        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenReturn(reservationDeny("BUDGET_EXCEEDED", "tenant/acme"));

        assertThatThrownBy(() -> advisor.adviseCall(request, chain))
                .isInstanceOf(CyclesBudgetDeniedException.class)
                .satisfies(ex -> {
                    CyclesBudgetDeniedException denied = (CyclesBudgetDeniedException) ex;
                    assertThat(denied.getReasonCode()).isEqualTo("BUDGET_EXCEEDED");
                    assertThat(denied.getScopePath()).isEqualTo("tenant/acme");
                });

        verifyNoInteractions(chain);
        verify(cyclesClient, never()).releaseReservation(anyString(), any(ReleaseRequest.class));
        verify(cyclesClient, never()).commitReservation(anyString(), any(CommitRequest.class));
    }

    // ---- Reserve fail-open / fail-closed --------------------------------

    @Test
    void reserveTransportFailureSurfacesWhenFailClosed() {
        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> advisor.adviseCall(request, chain))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cycles reservation failed");

        verifyNoInteractions(chain);
    }

    @Test
    void reserveTransportFailureProceedsWhenFailOpen() {
        springAiProperties.setFailOpen(true);
        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenThrow(new RuntimeException("connection refused"));
        when(chain.nextCall(request)).thenReturn(response);

        ChatClientResponse result = advisor.adviseCall(request, chain);

        assertThat(result).isSameAs(response);
        verify(chain).nextCall(request);
        verify(cyclesClient, never()).commitReservation(anyString(), any(CommitRequest.class));
    }

    @Test
    void reserveHttp5xxSurfacesWhenFailClosed() {
        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenReturn(CyclesResponse.httpError(503, "service unavailable", Map.of()));

        assertThatThrownBy(() -> advisor.adviseCall(request, chain))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("503");

        verifyNoInteractions(chain);
    }

    @Test
    void reserveHttp5xxProceedsWhenFailOpen() {
        springAiProperties.setFailOpen(true);
        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenReturn(CyclesResponse.httpError(503, "service unavailable", Map.of()));
        when(chain.nextCall(request)).thenReturn(response);

        ChatClientResponse result = advisor.adviseCall(request, chain);

        assertThat(result).isSameAs(response);
        verify(chain).nextCall(request);
        verify(cyclesClient, never()).commitReservation(anyString(), any(CommitRequest.class));
    }

    @Test
    void reserveResultUnparseableTreatedAsHttpFailure() {
        // 2xx response with a body that doesn't decode to a ReservationResult.
        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenReturn(CyclesResponse.success(200, null));

        assertThatThrownBy(() -> advisor.adviseCall(request, chain))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---- Commit fail-open / fail-closed ---------------------------------

    @Test
    void commitTransportFailureSurfacesWhenFailClosed() {
        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenReturn(reservationAllow("res-1"));
        when(chain.nextCall(request)).thenReturn(response);
        when(cyclesClient.commitReservation(anyString(), any(CommitRequest.class)))
                .thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> advisor.adviseCall(request, chain))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("commit failed");
    }

    @Test
    void commitTransportFailureSwallowedWhenFailOpen() {
        springAiProperties.setFailOpen(true);
        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenReturn(reservationAllow("res-1"));
        when(chain.nextCall(request)).thenReturn(response);
        when(cyclesClient.commitReservation(anyString(), any(CommitRequest.class)))
                .thenThrow(new RuntimeException("connection refused"));

        ChatClientResponse result = advisor.adviseCall(request, chain);

        assertThat(result).isSameAs(response);
    }

    @Test
    void commitHttpFailureSurfacesWhenFailClosed() {
        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenReturn(reservationAllow("res-1"));
        when(chain.nextCall(request)).thenReturn(response);
        when(cyclesClient.commitReservation(anyString(), any(CommitRequest.class)))
                .thenReturn(CyclesResponse.httpError(500, "boom", Map.of()));

        assertThatThrownBy(() -> advisor.adviseCall(request, chain))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("commit HTTP failure");
    }

    @Test
    void commitHttpFailureSwallowedWhenFailOpen() {
        springAiProperties.setFailOpen(true);
        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenReturn(reservationAllow("res-1"));
        when(chain.nextCall(request)).thenReturn(response);
        when(cyclesClient.commitReservation(anyString(), any(CommitRequest.class)))
                .thenReturn(CyclesResponse.httpError(500, "boom", Map.of()));

        ChatClientResponse result = advisor.adviseCall(request, chain);

        assertThat(result).isSameAs(response);
        verify(cyclesClient, never()).releaseReservation(anyString(), any(ReleaseRequest.class));
    }

    // ---- Release on chain exception -------------------------------------

    @Test
    void releasesReservationOnChainException() {
        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenReturn(reservationAllow("res-1"));
        RuntimeException callFailure = new RuntimeException("provider timeout");
        when(chain.nextCall(request)).thenThrow(callFailure);
        when(cyclesClient.releaseReservation(anyString(), any(ReleaseRequest.class)))
                .thenReturn(CyclesResponse.success(200, Map.of()));

        assertThatThrownBy(() -> advisor.adviseCall(request, chain))
                .isSameAs(callFailure);

        verify(cyclesClient).releaseReservation(eq("res-1"), any(ReleaseRequest.class));
        verify(cyclesClient, never()).commitReservation(anyString(), any(CommitRequest.class));
    }

    @Test
    void releaseFailureDoesNotMaskOriginalException() {
        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenReturn(reservationAllow("res-1"));
        RuntimeException callFailure = new RuntimeException("provider timeout");
        when(chain.nextCall(request)).thenThrow(callFailure);
        when(cyclesClient.releaseReservation(anyString(), any(ReleaseRequest.class)))
                .thenThrow(new RuntimeException("release transport error"));

        assertThatThrownBy(() -> advisor.adviseCall(request, chain))
                .isSameAs(callFailure);
    }

    @Test
    void failOpenReserveSkippedDoesNotReleaseOnChainException() {
        springAiProperties.setFailOpen(true);
        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenThrow(new RuntimeException("connection refused"));
        RuntimeException callFailure = new RuntimeException("provider timeout");
        when(chain.nextCall(request)).thenThrow(callFailure);

        assertThatThrownBy(() -> advisor.adviseCall(request, chain))
                .isSameAs(callFailure);

        verify(cyclesClient, never()).releaseReservation(anyString(), any(ReleaseRequest.class));
    }

    // ---- Estimate unit override -----------------------------------------

    @Test
    void unrecognizedEstimateUnitFallsBackToUsdMicrocents() {
        springAiProperties.setEstimateUnit("NOT_A_REAL_UNIT");
        ArgumentCaptor<ReservationCreateRequest> captor =
                ArgumentCaptor.forClass(ReservationCreateRequest.class);
        when(cyclesClient.createReservation(captor.capture()))
                .thenReturn(reservationAllow("res-1"));
        when(chain.nextCall(request)).thenReturn(response);
        when(cyclesClient.commitReservation(anyString(), any(CommitRequest.class)))
                .thenReturn(CyclesResponse.success(200, Map.of()));

        advisor.adviseCall(request, chain);

        assertThat(captor.getValue().getEstimate().getUnit().name()).isEqualTo("USD_MICROCENTS");
    }

    // ---- Helpers ---------------------------------------------------------

    private static CyclesResponse<Map<String, Object>> reservationAllow(String reservationId) {
        return CyclesResponse.success(200, Map.of(
                "decision", "ALLOW",
                "reservation_id", reservationId,
                "scope_path", "tenant/acme"
        ));
    }

    private static CyclesResponse<Map<String, Object>> reservationDeny(String reasonCode, String scopePath) {
        return CyclesResponse.success(200, Map.of(
                "decision", "DENY",
                "reason_code", reasonCode,
                "scope_path", scopePath
        ));
    }
}
