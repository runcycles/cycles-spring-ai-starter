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
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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

    @Test
    void reserveResultUnparseableProceedsWhenFailOpen() {
        // Mirror of the above with fail-open=true so the early-return branch in
        // reserveOrFailOpen (line 145) is exercised as a normal return, not as
        // a throw-propagation.
        springAiProperties.setFailOpen(true);
        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenReturn(CyclesResponse.success(200, null));
        when(chain.nextCall(request)).thenReturn(response);

        ChatClientResponse result = advisor.adviseCall(request, chain);

        assertThat(result).isSameAs(response);
        verify(cyclesClient, never()).commitReservation(anyString(), any(CommitRequest.class));
    }

    @Test
    void reserveMalformedDecisionTreatedAsHttpFailureWhenFailClosed() {
        // 2xx body with an unknown decision string — Decision.fromString returns null,
        // so isDenied() == false and isAllowed() == false. Must NOT silently bypass the
        // budget gate.
        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenReturn(CyclesResponse.success(200, Map.of(
                        "decision", "FUTURE_DECISION_VALUE",
                        "reservation_id", "res-1"
                )));

        assertThatThrownBy(() -> advisor.adviseCall(request, chain))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reservation HTTP failure");

        verifyNoInteractions(chain);
    }

    @Test
    void reserveMalformedDecisionProceedsWhenFailOpen() {
        springAiProperties.setFailOpen(true);
        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenReturn(CyclesResponse.success(200, Map.of(
                        "decision", "FUTURE_DECISION_VALUE",
                        "reservation_id", "res-1"
                )));
        when(chain.nextCall(request)).thenReturn(response);

        ChatClientResponse result = advisor.adviseCall(request, chain);

        assertThat(result).isSameAs(response);
        verify(cyclesClient, never()).commitReservation(anyString(), any(CommitRequest.class));
    }

    @Test
    void reserveAllowWithBlankReservationIdTreatedAsHttpFailure() {
        // ALLOW decision with whitespace-only reservation_id — should be treated
        // as malformed (same as missing reservation_id).
        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenReturn(CyclesResponse.success(200, Map.of(
                        "decision", "ALLOW",
                        "reservation_id", "   "
                )));

        assertThatThrownBy(() -> advisor.adviseCall(request, chain))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reservation HTTP failure");

        verifyNoInteractions(chain);
    }

    @Test
    void reserveAllowWithMissingReservationIdTreatedAsHttpFailureWhenFailClosed() {
        // ALLOW decision but no reservation_id — server-side bug or response truncation;
        // must not silently bypass the budget gate.
        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenReturn(CyclesResponse.success(200, Map.of(
                        "decision", "ALLOW"
                        // reservation_id deliberately omitted
                )));

        assertThatThrownBy(() -> advisor.adviseCall(request, chain))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reservation HTTP failure");

        verifyNoInteractions(chain);
    }

    // ---- Commit fail-open / fail-closed ---------------------------------

    @Test
    void commitFailureDoesNotReleaseReservation() {
        // After chain.nextCall succeeded, a commit failure must NOT release the
        // reservation — the LLM call already happened and budget was consumed.
        // Releasing would under-count actual spend.
        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenReturn(reservationAllow("res-1"));
        when(chain.nextCall(request)).thenReturn(response);
        when(cyclesClient.commitReservation(anyString(), any(CommitRequest.class)))
                .thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> advisor.adviseCall(request, chain))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("commit failed");

        verify(cyclesClient, never()).releaseReservation(anyString(), any(ReleaseRequest.class));
    }

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
    void releaseHttpFailureLoggedButDoesNotMaskOriginalException() {
        // DefaultCyclesClient returns CyclesResponse.httpError(...) rather than throwing
        // for HTTP failures. The release path must check is2xx() — a silent swallow
        // of a 500 from the release endpoint would leave reservations stuck.
        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenReturn(reservationAllow("res-1"));
        RuntimeException callFailure = new RuntimeException("provider timeout");
        when(chain.nextCall(request)).thenThrow(callFailure);
        when(cyclesClient.releaseReservation(anyString(), any(ReleaseRequest.class)))
                .thenReturn(CyclesResponse.httpError(500, "release-server-error", Map.of()));

        assertThatThrownBy(() -> advisor.adviseCall(request, chain))
                .isSameAs(callFailure);

        verify(cyclesClient).releaseReservation(anyString(), any(ReleaseRequest.class));
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

    // ---- Prompt-based reservation estimate (v0.2) -----------------------

    @Test
    void reservationUsesPromptBasedEstimateWhenEnabledAndRatesSet() {
        // estimate-from-prompt=true with rates configured: estimate is derived from
        // promptChars / 4 × (inputRate + outputRate).
        springAiProperties.setEstimateFromPrompt(true);
        springAiProperties.setInputCostPerToken(25L);
        springAiProperties.setOutputCostPerToken(100L);

        // A prompt with 80 chars → 20 estimated tokens → 20 * (25 + 100) = 2500.
        String promptText = "Summarize this passage from the operator manual: chapter 3 widget care";
        // Length is intentionally 70 chars — actual count below.
        Prompt prompt = new Prompt(List.of(new UserMessage(promptText)));
        when(request.prompt()).thenReturn(prompt);

        long expectedEstimate = (promptText.length() / 4L) * (25L + 100L);

        ArgumentCaptor<ReservationCreateRequest> reserveCaptor =
                ArgumentCaptor.forClass(ReservationCreateRequest.class);
        when(cyclesClient.createReservation(reserveCaptor.capture()))
                .thenReturn(reservationAllow("res-prompt-1"));
        when(chain.nextCall(request)).thenReturn(response);
        when(cyclesClient.commitReservation(anyString(), any(CommitRequest.class)))
                .thenReturn(CyclesResponse.success(200, Map.of()));

        advisor.adviseCall(request, chain);

        assertThat(reserveCaptor.getValue().getEstimate().getAmount()).isEqualTo(expectedEstimate);
    }

    @Test
    void reservationFallsBackToDefaultEstimateWhenPromptEstimationEnabledButRatesAreZero() {
        // estimate-from-prompt=true but no rates set: can't compute a meaningful
        // estimate from chars alone, fall back to default-estimate.
        springAiProperties.setEstimateFromPrompt(true);
        // rates remain 0 (default)

        Prompt prompt = new Prompt(List.of(new UserMessage("some prompt text")));
        when(request.prompt()).thenReturn(prompt);

        ArgumentCaptor<ReservationCreateRequest> reserveCaptor =
                ArgumentCaptor.forClass(ReservationCreateRequest.class);
        when(cyclesClient.createReservation(reserveCaptor.capture()))
                .thenReturn(reservationAllow("res-prompt-2"));
        when(chain.nextCall(request)).thenReturn(response);
        when(cyclesClient.commitReservation(anyString(), any(CommitRequest.class)))
                .thenReturn(CyclesResponse.success(200, Map.of()));

        advisor.adviseCall(request, chain);

        assertThat(reserveCaptor.getValue().getEstimate().getAmount()).isEqualTo(1000L); // default
    }

    @Test
    void reservationFallsBackToDefaultEstimateWhenPromptIsNull() {
        // request.prompt() returns null (unusual but defensible — defensive null check).
        springAiProperties.setEstimateFromPrompt(true);
        springAiProperties.setInputCostPerToken(25L);
        when(request.prompt()).thenReturn(null);

        ArgumentCaptor<ReservationCreateRequest> reserveCaptor =
                ArgumentCaptor.forClass(ReservationCreateRequest.class);
        when(cyclesClient.createReservation(reserveCaptor.capture()))
                .thenReturn(reservationAllow("res-null-prompt"));
        when(chain.nextCall(request)).thenReturn(response);
        when(cyclesClient.commitReservation(anyString(), any(CommitRequest.class)))
                .thenReturn(CyclesResponse.success(200, Map.of()));

        advisor.adviseCall(request, chain);

        assertThat(reserveCaptor.getValue().getEstimate().getAmount()).isEqualTo(1000L);
    }

    @Test
    void reservationSkipsMessagesWithNullText() {
        // Multimodal messages may have null .getText() (content lives in attachments).
        // Such messages are skipped in the char count rather than NPE'ing.
        springAiProperties.setEstimateFromPrompt(true);
        springAiProperties.setInputCostPerToken(25L);
        springAiProperties.setOutputCostPerToken(100L);

        Message nullTextMessage = mock(Message.class);
        when(nullTextMessage.getText()).thenReturn(null);
        Message validMessage = new UserMessage("hello world this is forty chars of text!!");

        Prompt prompt = new Prompt(List.of(nullTextMessage, validMessage));
        when(request.prompt()).thenReturn(prompt);

        long expectedEstimate = (((long) "hello world this is forty chars of text!!".length()) / 4L) * (25L + 100L);

        ArgumentCaptor<ReservationCreateRequest> reserveCaptor =
                ArgumentCaptor.forClass(ReservationCreateRequest.class);
        when(cyclesClient.createReservation(reserveCaptor.capture()))
                .thenReturn(reservationAllow("res-null-text"));
        when(chain.nextCall(request)).thenReturn(response);
        when(cyclesClient.commitReservation(anyString(), any(CommitRequest.class)))
                .thenReturn(CyclesResponse.success(200, Map.of()));

        advisor.adviseCall(request, chain);

        assertThat(reserveCaptor.getValue().getEstimate().getAmount()).isEqualTo(expectedEstimate);
    }

    @Test
    void reservationFallsBackToDefaultWhenComputedEstimateIsZero() {
        // Very short prompt + small rates → estimatedTokens=0 → estimate=0.
        // Falls back to default-estimate rather than committing a 0 reservation
        // (which the Cycles server might also accept but is semantically wrong).
        springAiProperties.setEstimateFromPrompt(true);
        springAiProperties.setInputCostPerToken(1L);
        // 2-char prompt / 4 chars-per-token = 0 estimated tokens
        Prompt prompt = new Prompt(List.of(new UserMessage("hi")));
        when(request.prompt()).thenReturn(prompt);

        ArgumentCaptor<ReservationCreateRequest> reserveCaptor =
                ArgumentCaptor.forClass(ReservationCreateRequest.class);
        when(cyclesClient.createReservation(reserveCaptor.capture()))
                .thenReturn(reservationAllow("res-zero"));
        when(chain.nextCall(request)).thenReturn(response);
        when(cyclesClient.commitReservation(anyString(), any(CommitRequest.class)))
                .thenReturn(CyclesResponse.success(200, Map.of()));

        advisor.adviseCall(request, chain);

        assertThat(reserveCaptor.getValue().getEstimate().getAmount()).isEqualTo(1000L); // fallback
    }

    @Test
    void reservationUsesPromptEstimateWithOnlyOutputRate() {
        // Cover the (inputRate > 0 || outputRate > 0) OR-short-circuit second branch:
        // inputRate=0, outputRate>0.
        springAiProperties.setEstimateFromPrompt(true);
        springAiProperties.setInputCostPerToken(0L);
        springAiProperties.setOutputCostPerToken(100L);

        String promptText = "Estimate from prompt size with only output rate configured.";
        Prompt prompt = new Prompt(List.of(new UserMessage(promptText)));
        when(request.prompt()).thenReturn(prompt);

        long expectedEstimate = (((long) promptText.length()) / 4L) * (0L + 100L);

        ArgumentCaptor<ReservationCreateRequest> reserveCaptor =
                ArgumentCaptor.forClass(ReservationCreateRequest.class);
        when(cyclesClient.createReservation(reserveCaptor.capture()))
                .thenReturn(reservationAllow("res-out-only"));
        when(chain.nextCall(request)).thenReturn(response);
        when(cyclesClient.commitReservation(anyString(), any(CommitRequest.class)))
                .thenReturn(CyclesResponse.success(200, Map.of()));

        advisor.adviseCall(request, chain);

        assertThat(reserveCaptor.getValue().getEstimate().getAmount()).isEqualTo(expectedEstimate);
    }

    @Test
    void reservationFallsBackToDefaultEstimateWhenPromptIsEmpty() {
        // estimate-from-prompt=true, rates set, but prompt has no text content.
        springAiProperties.setEstimateFromPrompt(true);
        springAiProperties.setInputCostPerToken(25L);

        Prompt prompt = new Prompt(List.of()); // no messages
        when(request.prompt()).thenReturn(prompt);

        ArgumentCaptor<ReservationCreateRequest> reserveCaptor =
                ArgumentCaptor.forClass(ReservationCreateRequest.class);
        when(cyclesClient.createReservation(reserveCaptor.capture()))
                .thenReturn(reservationAllow("res-prompt-3"));
        when(chain.nextCall(request)).thenReturn(response);
        when(cyclesClient.commitReservation(anyString(), any(CommitRequest.class)))
                .thenReturn(CyclesResponse.success(200, Map.of()));

        advisor.adviseCall(request, chain);

        assertThat(reserveCaptor.getValue().getEstimate().getAmount()).isEqualTo(1000L); // default
    }

    @Test
    void reservationUsesDefaultEstimateWhenPromptEstimationDisabled() {
        // Default behavior: estimate-from-prompt=false → lifecycle does not look at the
        // prompt at all, uses default-estimate. (No request.prompt() stub needed —
        // the code path never accesses it.)
        springAiProperties.setInputCostPerToken(25L);  // rates are set but prompt mode is off
        // estimateFromPrompt remains false (default)

        ArgumentCaptor<ReservationCreateRequest> reserveCaptor =
                ArgumentCaptor.forClass(ReservationCreateRequest.class);
        when(cyclesClient.createReservation(reserveCaptor.capture()))
                .thenReturn(reservationAllow("res-prompt-4"));
        when(chain.nextCall(request)).thenReturn(response);
        when(cyclesClient.commitReservation(anyString(), any(CommitRequest.class)))
                .thenReturn(CyclesResponse.success(200, Map.of()));

        advisor.adviseCall(request, chain);

        assertThat(reserveCaptor.getValue().getEstimate().getAmount()).isEqualTo(1000L); // default
    }

    // ---- Real Usage extraction on commit (v0.2) -------------------------

    @Test
    void commitUsesEstimateAsActualWhenNoUsageInResponse() {
        // Default ChatClientResponse mock has chatResponse()=null → fallback to estimate.
        ArgumentCaptor<CommitRequest> commitCaptor = ArgumentCaptor.forClass(CommitRequest.class);
        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenReturn(reservationAllow("res-1"));
        when(chain.nextCall(request)).thenReturn(response);
        when(cyclesClient.commitReservation(anyString(), commitCaptor.capture()))
                .thenReturn(CyclesResponse.success(200, Map.of()));

        advisor.adviseCall(request, chain);

        CommitRequest sent = commitCaptor.getValue();
        assertThat(sent.getActual().getAmount()).isEqualTo(1000L); // default estimate
        assertThat(sent.getActual().getUnit().name()).isEqualTo("USD_MICROCENTS");
    }

    @Test
    void commitUsesComputedCostWhenRatesAndUsagePresent() {
        // OpenAI gpt-4o-style rates: input=25 µ¢/tok, output=100 µ¢/tok.
        // 100 prompt + 50 completion → (100*25) + (50*100) = 2500 + 5000 = 7500 µ¢
        springAiProperties.setInputCostPerToken(25L);
        springAiProperties.setOutputCostPerToken(100L);

        ChatResponse chatResponse = mock(ChatResponse.class);
        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        Usage usage = mock(Usage.class);
        when(response.chatResponse()).thenReturn(chatResponse);
        when(chatResponse.getMetadata()).thenReturn(metadata);
        when(metadata.getUsage()).thenReturn(usage);
        when(usage.getPromptTokens()).thenReturn(100);
        when(usage.getCompletionTokens()).thenReturn(50);

        ArgumentCaptor<CommitRequest> commitCaptor = ArgumentCaptor.forClass(CommitRequest.class);
        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenReturn(reservationAllow("res-1"));
        when(chain.nextCall(request)).thenReturn(response);
        when(cyclesClient.commitReservation(anyString(), commitCaptor.capture()))
                .thenReturn(CyclesResponse.success(200, Map.of()));

        advisor.adviseCall(request, chain);

        CommitRequest sent = commitCaptor.getValue();
        assertThat(sent.getActual().getAmount()).isEqualTo(7500L);
        assertThat(sent.getActual().getUnit().name()).isEqualTo("USD_MICROCENTS");
    }

    @Test
    void commitUsesTotalTokensWhenUnitIsTokensAndUsagePresent() {
        // Unit=TOKENS bypasses cost rates — commit total tokens directly.
        springAiProperties.setEstimateUnit("TOKENS");

        ChatResponse chatResponse = mock(ChatResponse.class);
        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        Usage usage = mock(Usage.class);
        when(response.chatResponse()).thenReturn(chatResponse);
        when(chatResponse.getMetadata()).thenReturn(metadata);
        when(metadata.getUsage()).thenReturn(usage);
        when(usage.getTotalTokens()).thenReturn(150);

        ArgumentCaptor<CommitRequest> commitCaptor = ArgumentCaptor.forClass(CommitRequest.class);
        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenReturn(reservationAllow("res-1"));
        when(chain.nextCall(request)).thenReturn(response);
        when(cyclesClient.commitReservation(anyString(), commitCaptor.capture()))
                .thenReturn(CyclesResponse.success(200, Map.of()));

        advisor.adviseCall(request, chain);

        CommitRequest sent = commitCaptor.getValue();
        assertThat(sent.getActual().getAmount()).isEqualTo(150L);
        assertThat(sent.getActual().getUnit().name()).isEqualTo("TOKENS");
    }

    @Test
    void commitFallsBackToEstimateWhenRatesSetButUsageMissing() {
        // Rates configured but provider returned no usage info — estimate-as-actual.
        springAiProperties.setInputCostPerToken(25L);
        springAiProperties.setOutputCostPerToken(100L);

        ChatResponse chatResponse = mock(ChatResponse.class);
        when(response.chatResponse()).thenReturn(chatResponse);
        when(chatResponse.getMetadata()).thenReturn(null); // metadata missing

        ArgumentCaptor<CommitRequest> commitCaptor = ArgumentCaptor.forClass(CommitRequest.class);
        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenReturn(reservationAllow("res-1"));
        when(chain.nextCall(request)).thenReturn(response);
        when(cyclesClient.commitReservation(anyString(), commitCaptor.capture()))
                .thenReturn(CyclesResponse.success(200, Map.of()));

        advisor.adviseCall(request, chain);

        CommitRequest sent = commitCaptor.getValue();
        assertThat(sent.getActual().getAmount()).isEqualTo(1000L); // estimate fallback
    }

    @Test
    void commitFallsBackToEstimateWhenMetadataUsageIsNull() {
        // ChatResponse + metadata are present, but metadata.getUsage() returns null.
        // Some providers omit usage on streaming-aborted responses or certain errors.
        springAiProperties.setInputCostPerToken(25L);

        ChatResponse chatResponse = mock(ChatResponse.class);
        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        when(response.chatResponse()).thenReturn(chatResponse);
        when(chatResponse.getMetadata()).thenReturn(metadata);
        when(metadata.getUsage()).thenReturn(null);

        ArgumentCaptor<CommitRequest> commitCaptor = ArgumentCaptor.forClass(CommitRequest.class);
        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenReturn(reservationAllow("res-1"));
        when(chain.nextCall(request)).thenReturn(response);
        when(cyclesClient.commitReservation(anyString(), commitCaptor.capture()))
                .thenReturn(CyclesResponse.success(200, Map.of()));

        advisor.adviseCall(request, chain);

        CommitRequest sent = commitCaptor.getValue();
        assertThat(sent.getActual().getAmount()).isEqualTo(1000L); // estimate fallback
    }

    @Test
    void commitWithTokensUnitFallsBackToEstimateWhenUsageMissing() {
        // unit=TOKENS but no usage at all in response — fall through to estimate path.
        springAiProperties.setEstimateUnit("TOKENS");

        // response.chatResponse() returns null → usage is null
        ArgumentCaptor<CommitRequest> commitCaptor = ArgumentCaptor.forClass(CommitRequest.class);
        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenReturn(reservationAllow("res-1"));
        when(chain.nextCall(request)).thenReturn(response);
        when(cyclesClient.commitReservation(anyString(), commitCaptor.capture()))
                .thenReturn(CyclesResponse.success(200, Map.of()));

        advisor.adviseCall(request, chain);

        CommitRequest sent = commitCaptor.getValue();
        assertThat(sent.getActual().getAmount()).isEqualTo(1000L); // estimate fallback
        assertThat(sent.getActual().getUnit().name()).isEqualTo("TOKENS");
    }

    @Test
    void commitUsesOnlyOutputRateWhenInputRateIsZero() {
        // inputCostPerToken=0, outputCostPerToken>0. Should still compute from usage
        // using the output rate alone (covers the OR-short-circuit second branch).
        springAiProperties.setInputCostPerToken(0L);
        springAiProperties.setOutputCostPerToken(100L);

        ChatResponse chatResponse = mock(ChatResponse.class);
        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        Usage usage = mock(Usage.class);
        when(response.chatResponse()).thenReturn(chatResponse);
        when(chatResponse.getMetadata()).thenReturn(metadata);
        when(metadata.getUsage()).thenReturn(usage);
        when(usage.getPromptTokens()).thenReturn(100);
        when(usage.getCompletionTokens()).thenReturn(50);

        ArgumentCaptor<CommitRequest> commitCaptor = ArgumentCaptor.forClass(CommitRequest.class);
        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenReturn(reservationAllow("res-1"));
        when(chain.nextCall(request)).thenReturn(response);
        when(cyclesClient.commitReservation(anyString(), commitCaptor.capture()))
                .thenReturn(CyclesResponse.success(200, Map.of()));

        advisor.adviseCall(request, chain);

        CommitRequest sent = commitCaptor.getValue();
        // (100 * 0) + (50 * 100) = 5000
        assertThat(sent.getActual().getAmount()).isEqualTo(5000L);
    }

    @Test
    void commitWithTokensUnitFallsBackToEstimateWhenTotalTokensNull() {
        // unit=TOKENS but provider returned no total — fall through to rate / estimate path.
        // Since rates aren't configured here either, end result is estimate-as-actual.
        springAiProperties.setEstimateUnit("TOKENS");

        ChatResponse chatResponse = mock(ChatResponse.class);
        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        Usage usage = mock(Usage.class);
        when(response.chatResponse()).thenReturn(chatResponse);
        when(chatResponse.getMetadata()).thenReturn(metadata);
        when(metadata.getUsage()).thenReturn(usage);
        when(usage.getTotalTokens()).thenReturn(null);

        ArgumentCaptor<CommitRequest> commitCaptor = ArgumentCaptor.forClass(CommitRequest.class);
        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenReturn(reservationAllow("res-1"));
        when(chain.nextCall(request)).thenReturn(response);
        when(cyclesClient.commitReservation(anyString(), commitCaptor.capture()))
                .thenReturn(CyclesResponse.success(200, Map.of()));

        advisor.adviseCall(request, chain);

        CommitRequest sent = commitCaptor.getValue();
        assertThat(sent.getActual().getAmount()).isEqualTo(1000L);
        assertThat(sent.getActual().getUnit().name()).isEqualTo("TOKENS"); // unit preserved
    }

    @Test
    void commitHandlesNullTokenCountsInUsage() {
        // Usage object exists but tokens are null (some providers do this on errors).
        springAiProperties.setInputCostPerToken(25L);
        springAiProperties.setOutputCostPerToken(100L);

        ChatResponse chatResponse = mock(ChatResponse.class);
        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        Usage usage = mock(Usage.class);
        when(response.chatResponse()).thenReturn(chatResponse);
        when(chatResponse.getMetadata()).thenReturn(metadata);
        when(metadata.getUsage()).thenReturn(usage);
        // Both token counts null
        when(usage.getPromptTokens()).thenReturn(null);
        when(usage.getCompletionTokens()).thenReturn(null);

        ArgumentCaptor<CommitRequest> commitCaptor = ArgumentCaptor.forClass(CommitRequest.class);
        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenReturn(reservationAllow("res-1"));
        when(chain.nextCall(request)).thenReturn(response);
        when(cyclesClient.commitReservation(anyString(), commitCaptor.capture()))
                .thenReturn(CyclesResponse.success(200, Map.of()));

        advisor.adviseCall(request, chain);

        CommitRequest sent = commitCaptor.getValue();
        // nullSafeLong → 0 for both, so 0*25 + 0*100 = 0 → commits zero, not estimate.
        // (This is intentional: usage present with zeros is a real signal of "no work done"
        // — the LLM call happened but no tokens consumed. Different from "usage missing".)
        assertThat(sent.getActual().getAmount()).isEqualTo(0L);
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
