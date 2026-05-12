package io.runcycles.client.java.springai;

import io.runcycles.client.java.spring.client.CyclesClient;
import io.runcycles.client.java.spring.config.CyclesProperties;
import io.runcycles.client.java.spring.model.CommitRequest;
import io.runcycles.client.java.spring.model.CyclesResponse;
import io.runcycles.client.java.spring.model.ReleaseRequest;
import io.runcycles.client.java.spring.model.ReservationCreateRequest;
import io.runcycles.client.java.springai.advisor.CyclesBudgetStreamAdvisor;
import io.runcycles.client.java.springai.autoconfigure.CyclesSpringAiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CyclesBudgetStreamAdvisor}. Uses Mockito to stub Cycles wire
 * calls and Reactor's StepVerifier to drive the Flux lifecycle (complete / error /
 * cancel) without a real provider.
 */
@ExtendWith(MockitoExtension.class)
class CyclesBudgetStreamAdvisorTest {

    @Mock CyclesClient cyclesClient;
    @Mock StreamAdvisorChain chain;
    @Mock ChatClientRequest request;
    @Mock ChatClientResponse chunk1;
    @Mock ChatClientResponse chunk2;

    private CyclesProperties cyclesProperties;
    private CyclesSpringAiProperties springAiProperties;
    private CyclesBudgetStreamAdvisor advisor;

    @BeforeEach
    void setUp() {
        cyclesProperties = new CyclesProperties();
        cyclesProperties.setTenant("acme");
        cyclesProperties.setWorkspace("dev");
        cyclesProperties.setApp("spring-ai");

        springAiProperties = new CyclesSpringAiProperties();

        advisor = new CyclesBudgetStreamAdvisor(cyclesClient, cyclesProperties, springAiProperties);
    }

    // ---- Metadata --------------------------------------------------------

    @Test
    void nameIsCyclesBudgetStream() {
        assertThat(advisor.getName()).isEqualTo("cycles-budget-stream");
    }

    @Test
    void orderMatchesCallAdvisor() {
        assertThat(advisor.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 100);
    }

    // ---- Happy path: stream completes → commit ---------------------------

    @Test
    void streamCompleteCommitsReservation() {
        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenReturn(reservationAllow("res-1"));
        when(chain.nextStream(request)).thenReturn(Flux.just(chunk1, chunk2));
        when(cyclesClient.commitReservation(eq("res-1"), any(CommitRequest.class)))
                .thenReturn(CyclesResponse.success(200, Map.of()));

        StepVerifier.create(advisor.adviseStream(request, chain))
                .expectNext(chunk1, chunk2)
                .verifyComplete();

        verify(cyclesClient).commitReservation(eq("res-1"), any(CommitRequest.class));
        verify(cyclesClient, never()).releaseReservation(anyString(), any(ReleaseRequest.class));
    }

    @Test
    void streamCompleteUsesLastChunkUsageForCommit() {
        // Provider populates Usage only on the last chunk (typical of OpenAI streaming).
        springAiProperties.setInputCostPerToken(25L);
        springAiProperties.setOutputCostPerToken(100L);

        // chunk1 has no usage (mock default — chatResponse() returns null)
        // chunk2 has full usage
        ChatResponse last = mock(ChatResponse.class);
        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        Usage usage = mock(Usage.class);
        when(chunk2.chatResponse()).thenReturn(last);
        when(last.getMetadata()).thenReturn(metadata);
        when(metadata.getUsage()).thenReturn(usage);
        when(usage.getPromptTokens()).thenReturn(200);
        when(usage.getCompletionTokens()).thenReturn(80);

        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenReturn(reservationAllow("res-2"));
        when(chain.nextStream(request)).thenReturn(Flux.just(chunk1, chunk2));
        ArgumentCaptor<CommitRequest> commitCaptor = ArgumentCaptor.forClass(CommitRequest.class);
        when(cyclesClient.commitReservation(anyString(), commitCaptor.capture()))
                .thenReturn(CyclesResponse.success(200, Map.of()));

        StepVerifier.create(advisor.adviseStream(request, chain))
                .expectNext(chunk1, chunk2)
                .verifyComplete();

        // (200 * 25) + (80 * 100) = 5000 + 8000 = 13000
        assertThat(commitCaptor.getValue().getActual().getAmount()).isEqualTo(13000L);
    }

    // ---- Error path: stream errors → release -----------------------------

    @Test
    void streamErrorReleasesReservation() {
        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenReturn(reservationAllow("res-3"));
        RuntimeException streamFailure = new RuntimeException("provider timeout");
        when(chain.nextStream(request)).thenReturn(Flux.error(streamFailure));
        when(cyclesClient.releaseReservation(anyString(), any(ReleaseRequest.class)))
                .thenReturn(CyclesResponse.success(200, Map.of()));

        StepVerifier.create(advisor.adviseStream(request, chain))
                .verifyErrorMatches(t -> t == streamFailure);

        verify(cyclesClient).releaseReservation(eq("res-3"), any(ReleaseRequest.class));
        verify(cyclesClient, never()).commitReservation(anyString(), any(CommitRequest.class));
    }

    @Test
    void streamErrorAfterPartialEmissionStillReleases() {
        // Emit chunk1, then error. Commit should not happen, release should.
        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenReturn(reservationAllow("res-4"));
        RuntimeException streamFailure = new RuntimeException("mid-stream failure");
        when(chain.nextStream(request))
                .thenReturn(Flux.just(chunk1).concatWith(Flux.error(streamFailure)));
        when(cyclesClient.releaseReservation(anyString(), any(ReleaseRequest.class)))
                .thenReturn(CyclesResponse.success(200, Map.of()));

        StepVerifier.create(advisor.adviseStream(request, chain))
                .expectNext(chunk1)
                .verifyErrorMatches(t -> t == streamFailure);

        verify(cyclesClient).releaseReservation(eq("res-4"), any(ReleaseRequest.class));
        verify(cyclesClient, never()).commitReservation(anyString(), any(CommitRequest.class));
    }

    // ---- Cancel path: subscriber cancels → release -----------------------

    @Test
    void streamCancelReleasesReservation() {
        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenReturn(reservationAllow("res-5"));
        // Emit a few items but the consumer cancels after taking 1.
        when(chain.nextStream(request)).thenReturn(Flux.just(chunk1, chunk2));
        when(cyclesClient.releaseReservation(anyString(), any(ReleaseRequest.class)))
                .thenReturn(CyclesResponse.success(200, Map.of()));

        StepVerifier.create(advisor.adviseStream(request, chain).take(1))
                .expectNext(chunk1)
                .verifyComplete();

        verify(cyclesClient).releaseReservation(eq("res-5"), any(ReleaseRequest.class));
        verify(cyclesClient, never()).commitReservation(anyString(), any(CommitRequest.class));
    }

    // ---- Reservation denial --------------------------------------------

    @Test
    void reserveDenyThrowsSynchronouslyWithoutSubscribing() {
        // CyclesBudgetDeniedException is thrown from reserveOrFailOpen during
        // adviseStream invocation, BEFORE the chain is subscribed. The Flux is never
        // returned — the exception propagates up to the caller.
        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenReturn(CyclesResponse.success(200, Map.of(
                        "decision", "DENY",
                        "reason_code", "BUDGET_EXCEEDED",
                        "scope_path", "tenant/acme"
                )));

        try {
            advisor.adviseStream(request, chain);
            assertThat(false).as("expected CyclesBudgetDeniedException").isTrue();
        } catch (CyclesBudgetDeniedException denied) {
            assertThat(denied.getReasonCode()).isEqualTo("BUDGET_EXCEEDED");
        }

        verifyNoInteractions(chain);
    }

    @Test
    void emptyStreamCompletesAndCommitsWithEstimate() {
        // Flux completes without emitting any chunks. lastResponse stays null, so
        // the lifecycle commits the estimate as actual (no usage to derive from).
        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenReturn(reservationAllow("res-empty"));
        when(chain.nextStream(request)).thenReturn(Flux.empty());
        ArgumentCaptor<CommitRequest> commitCaptor = ArgumentCaptor.forClass(CommitRequest.class);
        when(cyclesClient.commitReservation(anyString(), commitCaptor.capture()))
                .thenReturn(CyclesResponse.success(200, Map.of()));

        StepVerifier.create(advisor.adviseStream(request, chain))
                .verifyComplete();

        verify(cyclesClient).commitReservation(eq("res-empty"), any(CommitRequest.class));
        assertThat(commitCaptor.getValue().getActual().getAmount()).isEqualTo(1000L); // default estimate
    }

    @Test
    void failOpenReserveSkipsReleaseOnStreamError() {
        // fail-open + reserve transport failure → reservationId=null. When stream then
        // errors, release should NOT be called (no reservation to release).
        springAiProperties.setFailOpen(true);
        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenThrow(new RuntimeException("connection refused"));
        RuntimeException streamFailure = new RuntimeException("provider error");
        when(chain.nextStream(request)).thenReturn(Flux.error(streamFailure));

        StepVerifier.create(advisor.adviseStream(request, chain))
                .verifyErrorMatches(t -> t == streamFailure);

        verify(cyclesClient, never()).releaseReservation(anyString(), any(ReleaseRequest.class));
        verify(cyclesClient, never()).commitReservation(anyString(), any(CommitRequest.class));
    }

    @Test
    void failOpenReserveSkipsReleaseOnStreamCancel() {
        // fail-open + reserve transport failure → reservationId=null. When subscriber
        // cancels, release should NOT be called.
        springAiProperties.setFailOpen(true);
        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenThrow(new RuntimeException("connection refused"));
        when(chain.nextStream(request)).thenReturn(Flux.just(chunk1, chunk2));

        StepVerifier.create(advisor.adviseStream(request, chain).take(1))
                .expectNext(chunk1)
                .verifyComplete();

        verify(cyclesClient, never()).releaseReservation(anyString(), any(ReleaseRequest.class));
        verify(cyclesClient, never()).commitReservation(anyString(), any(CommitRequest.class));
    }

    // ---- Prompt-based reservation estimate also applies to streaming ---

    @Test
    void streamReservationUsesPromptBasedEstimateWhenEnabledAndRatesSet() {
        // Pins the contract that estimate-from-prompt works for the stream advisor too,
        // not just the call advisor — both route through the shared CyclesBudgetLifecycle.
        springAiProperties.setEstimateFromPrompt(true);
        springAiProperties.setInputCostPerToken(25L);
        springAiProperties.setOutputCostPerToken(100L);

        String promptText = "Stream this summary of the operator manual for chapter 3.";
        Prompt prompt = new Prompt(List.of(new UserMessage(promptText)));
        when(request.prompt()).thenReturn(prompt);

        long expectedEstimate = (((long) promptText.length()) / 4L) * (25L + 100L);

        ArgumentCaptor<ReservationCreateRequest> reserveCaptor =
                ArgumentCaptor.forClass(ReservationCreateRequest.class);
        when(cyclesClient.createReservation(reserveCaptor.capture()))
                .thenReturn(reservationAllow("res-stream-prompt"));
        when(chain.nextStream(request)).thenReturn(Flux.just(chunk1, chunk2));
        when(cyclesClient.commitReservation(anyString(), any(CommitRequest.class)))
                .thenReturn(CyclesResponse.success(200, Map.of()));

        StepVerifier.create(advisor.adviseStream(request, chain))
                .expectNext(chunk1, chunk2)
                .verifyComplete();

        assertThat(reserveCaptor.getValue().getEstimate().getAmount()).isEqualTo(expectedEstimate);
    }

    @Test
    void reserveTransportFailureProceedsWhenFailOpen() {
        // fail-open=true → reservation is null → stream proceeds without budget gate.
        // commit/release are skipped because there's no reservation.
        springAiProperties.setFailOpen(true);
        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenThrow(new RuntimeException("connection refused"));
        when(chain.nextStream(request)).thenReturn(Flux.just(chunk1, chunk2));

        StepVerifier.create(advisor.adviseStream(request, chain))
                .expectNext(chunk1, chunk2)
                .verifyComplete();

        verify(cyclesClient, never()).commitReservation(anyString(), any(CommitRequest.class));
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
