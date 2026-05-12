package io.runcycles.client.java.springai.tool;

import io.runcycles.client.java.spring.client.CyclesClient;
import io.runcycles.client.java.spring.config.CyclesProperties;
import io.runcycles.client.java.spring.model.CommitRequest;
import io.runcycles.client.java.spring.model.CyclesResponse;
import io.runcycles.client.java.spring.model.ReleaseRequest;
import io.runcycles.client.java.spring.model.ReservationCreateRequest;
import io.runcycles.client.java.springai.CyclesBudgetDeniedException;
import io.runcycles.client.java.springai.autoconfigure.CyclesSpringAiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CyclesToolCallback}. Verifies the reserve / commit / release
 * lifecycle around a wrapped Spring AI ToolCallback.
 */
@ExtendWith(MockitoExtension.class)
class CyclesToolCallbackTest {

    @Mock CyclesClient cyclesClient;
    @Mock ToolCallback delegate;
    @Mock ToolDefinition toolDefinition;
    @Mock ToolMetadata toolMetadata;

    private CyclesProperties cyclesProperties;
    private CyclesSpringAiProperties springAiProperties;
    private CyclesToolCallback gatedTool;

    @BeforeEach
    void setUp() {
        cyclesProperties = new CyclesProperties();
        cyclesProperties.setTenant("acme");

        springAiProperties = new CyclesSpringAiProperties();

        // delegate.getToolDefinition() is called by the gateInvocation path to build the
        // tool-specific action name. Tests that exercise that path stub toolDefinition.name()
        // explicitly; the simple delegation tests don't need to.
        lenient().when(delegate.getToolDefinition()).thenReturn(toolDefinition);
        lenient().when(toolDefinition.name()).thenReturn("get_weather");

        gatedTool = new CyclesToolCallback(delegate, cyclesClient, cyclesProperties, springAiProperties);
    }

    // ---- Delegation ------------------------------------------------------

    @Test
    void delegatesToolDefinition() {
        assertThat(gatedTool.getToolDefinition()).isSameAs(toolDefinition);
    }

    @Test
    void delegatesToolMetadata() {
        when(delegate.getToolMetadata()).thenReturn(toolMetadata);
        assertThat(gatedTool.getToolMetadata()).isSameAs(toolMetadata);
    }

    // ---- Happy path ------------------------------------------------------

    @Test
    void toolCallReservesCommitsAndReturnsResult() {
        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenReturn(reservationAllow("res-tool-1"));
        when(delegate.call("input-json")).thenReturn("tool-result");
        when(cyclesClient.commitReservation(eq("res-tool-1"), any(CommitRequest.class)))
                .thenReturn(CyclesResponse.success(200, Map.of()));

        String result = gatedTool.call("input-json");

        assertThat(result).isEqualTo("tool-result");
        verify(cyclesClient).commitReservation(eq("res-tool-1"), any(CommitRequest.class));
        verify(cyclesClient, never()).releaseReservation(anyString(), any(ReleaseRequest.class));
    }

    @Test
    void toolCallWithToolContextSameLifecycle() {
        ToolContext context = mock(ToolContext.class);
        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenReturn(reservationAllow("res-tool-2"));
        when(delegate.call("input", context)).thenReturn("contextual-result");
        when(cyclesClient.commitReservation(anyString(), any(CommitRequest.class)))
                .thenReturn(CyclesResponse.success(200, Map.of()));

        String result = gatedTool.call("input", context);

        assertThat(result).isEqualTo("contextual-result");
        verify(cyclesClient).commitReservation(eq("res-tool-2"), any(CommitRequest.class));
    }

    @Test
    void toolReservationFallsBackToDefaultEstimateEvenWhenPromptBasedEnabled() {
        // estimate-from-prompt=true is a chat-only feature. Tools don't have a prompt,
        // so the lifecycle skips prompt-based estimation when called from a tool
        // (request=null path).
        springAiProperties.setEstimateFromPrompt(true);
        springAiProperties.setInputCostPerToken(25L);

        ArgumentCaptor<ReservationCreateRequest> reserveCaptor =
                ArgumentCaptor.forClass(ReservationCreateRequest.class);
        when(cyclesClient.createReservation(reserveCaptor.capture()))
                .thenReturn(reservationAllow("res-tool-no-prompt"));
        when(delegate.call("x")).thenReturn("y");
        when(cyclesClient.commitReservation(anyString(), any(CommitRequest.class)))
                .thenReturn(CyclesResponse.success(200, Map.of()));

        gatedTool.call("x");

        // Reservation amount is the default-estimate (1000), not a prompt-derived value.
        assertThat(reserveCaptor.getValue().getEstimate().getAmount()).isEqualTo(1000L);
    }

    @Test
    void reservationCarriesToolActionLabelsAndPrefixedToolName() {
        // Default labels: tool-action-kind=tool.call, tool-action-name-prefix=spring-ai-tool:
        // Wrapped tool name=get_weather → reported action.name=spring-ai-tool:get_weather.
        ArgumentCaptor<ReservationCreateRequest> reserveCaptor =
                ArgumentCaptor.forClass(ReservationCreateRequest.class);
        when(cyclesClient.createReservation(reserveCaptor.capture()))
                .thenReturn(reservationAllow("res-tool-3"));
        when(delegate.call("x")).thenReturn("y");
        when(cyclesClient.commitReservation(anyString(), any(CommitRequest.class)))
                .thenReturn(CyclesResponse.success(200, Map.of()));

        gatedTool.call("x");

        ReservationCreateRequest sent = reserveCaptor.getValue();
        assertThat(sent.getAction().getKind()).isEqualTo("tool.call");
        assertThat(sent.getAction().getName()).isEqualTo("spring-ai-tool:get_weather");
    }

    // ---- Denial path -----------------------------------------------------

    @Test
    void reserveDenyThrowsAndSkipsToolInvocation() {
        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenReturn(CyclesResponse.success(200, Map.of(
                        "decision", "DENY",
                        "reason_code", "BUDGET_EXCEEDED",
                        "scope_path", "tenant/acme"
                )));

        assertThatThrownBy(() -> gatedTool.call("input"))
                .isInstanceOf(CyclesBudgetDeniedException.class);

        verify(delegate, never()).call(anyString());
        verify(delegate, never()).call(anyString(), any(ToolContext.class));
    }

    // ---- Tool failure → release ------------------------------------------

    @Test
    void toolInvocationExceptionReleasesReservation() {
        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenReturn(reservationAllow("res-tool-fail"));
        RuntimeException toolFailure = new RuntimeException("upstream API down");
        when(delegate.call("bad-input")).thenThrow(toolFailure);
        when(cyclesClient.releaseReservation(anyString(), any(ReleaseRequest.class)))
                .thenReturn(CyclesResponse.success(200, Map.of()));

        assertThatThrownBy(() -> gatedTool.call("bad-input"))
                .isSameAs(toolFailure);

        verify(cyclesClient).releaseReservation(eq("res-tool-fail"), any(ReleaseRequest.class));
        verify(cyclesClient, never()).commitReservation(anyString(), any(CommitRequest.class));
    }

    // ---- Fail-open ------------------------------------------------------

    @Test
    void reserveTransportFailureProceedsWhenFailOpen() {
        springAiProperties.setFailOpen(true);
        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenThrow(new RuntimeException("connection refused"));
        when(delegate.call("x")).thenReturn("result-without-reservation");

        String result = gatedTool.call("x");

        assertThat(result).isEqualTo("result-without-reservation");
        verify(cyclesClient, never()).commitReservation(anyString(), any(CommitRequest.class));
        verify(cyclesClient, never()).releaseReservation(anyString(), any(ReleaseRequest.class));
    }

    @Test
    void failOpenReserveSkipsReleaseOnToolException() {
        // Reserve fails fail-open → reservationId=null. When tool then throws, we
        // shouldn't try to release (no reservation to release).
        springAiProperties.setFailOpen(true);
        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenThrow(new RuntimeException("connection refused"));
        RuntimeException toolFailure = new RuntimeException("upstream failure");
        when(delegate.call("x")).thenThrow(toolFailure);

        assertThatThrownBy(() -> gatedTool.call("x")).isSameAs(toolFailure);

        verify(cyclesClient, never()).releaseReservation(anyString(), any(ReleaseRequest.class));
    }

    // ---- Helpers ---------------------------------------------------------

    private static CyclesResponse<Map<String, Object>> reservationAllow(String reservationId) {
        return CyclesResponse.success(200, Map.of(
                "decision", "ALLOW",
                "reservation_id", reservationId,
                "scope_path", "tenant/acme"
        ));
    }
}
