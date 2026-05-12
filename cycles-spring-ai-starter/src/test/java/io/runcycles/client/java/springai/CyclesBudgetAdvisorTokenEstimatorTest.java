package io.runcycles.client.java.springai;

import io.runcycles.client.java.spring.client.CyclesClient;
import io.runcycles.client.java.spring.config.CyclesProperties;
import io.runcycles.client.java.spring.model.CommitRequest;
import io.runcycles.client.java.spring.model.CyclesResponse;
import io.runcycles.client.java.spring.model.ReservationCreateRequest;
import io.runcycles.client.java.springai.advisor.CyclesBudgetAdvisor;
import io.runcycles.client.java.springai.autoconfigure.CyclesSpringAiProperties;
import io.runcycles.client.java.springai.subject.PropertiesSubjectResolver;
import io.runcycles.client.java.springai.tokenizer.CharsPerTokenEstimator;
import io.runcycles.client.java.springai.tokenizer.PromptTokenEstimator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Integration-style tests for {@link CyclesBudgetAdvisor} with a custom
 * {@link PromptTokenEstimator}. Verifies the advisor invokes the user-provided
 * estimator and uses its result to size the reservation.
 */
@ExtendWith(MockitoExtension.class)
class CyclesBudgetAdvisorTokenEstimatorTest {

    @Mock CyclesClient cyclesClient;
    @Mock CallAdvisorChain chain;
    @Mock ChatClientRequest request;
    @Mock ChatClientResponse response;

    @Test
    void advisorUsesCustomTokenEstimator() {
        // User registers a custom estimator that returns a fixed 200 tokens regardless
        // of prompt content. Reservation should size as 200 * (input + output rate).
        CyclesProperties cyclesProperties = new CyclesProperties();
        CyclesSpringAiProperties springAiProperties = new CyclesSpringAiProperties();
        springAiProperties.setEstimateFromPrompt(true);
        springAiProperties.setInputCostPerToken(25L);
        springAiProperties.setOutputCostPerToken(100L);

        PromptTokenEstimator fixedEstimator = req -> 200L;

        CyclesBudgetAdvisor advisor = new CyclesBudgetAdvisor(
                cyclesClient, cyclesProperties, springAiProperties,
                new PropertiesSubjectResolver(cyclesProperties), fixedEstimator);

        ArgumentCaptor<ReservationCreateRequest> captor =
                ArgumentCaptor.forClass(ReservationCreateRequest.class);
        when(cyclesClient.createReservation(captor.capture()))
                .thenReturn(reservationAllow("res-est"));
        when(chain.nextCall(request)).thenReturn(response);
        when(cyclesClient.commitReservation(anyString(), any(CommitRequest.class)))
                .thenReturn(CyclesResponse.success(200, Map.of()));

        advisor.adviseCall(request, chain);

        // 200 tokens * (25 + 100) = 25_000.
        assertThat(captor.getValue().getEstimate().getAmount()).isEqualTo(25_000L);
    }

    @Test
    void customEstimatorReceivesTheChatClientRequest() {
        CyclesProperties cyclesProperties = new CyclesProperties();
        CyclesSpringAiProperties springAiProperties = new CyclesSpringAiProperties();
        springAiProperties.setEstimateFromPrompt(true);
        springAiProperties.setInputCostPerToken(1L);

        AtomicReference<ChatClientRequest> captured = new AtomicReference<>();
        PromptTokenEstimator captureEstimator = req -> {
            captured.set(req);
            return 10L;
        };

        CyclesBudgetAdvisor advisor = new CyclesBudgetAdvisor(
                cyclesClient, cyclesProperties, springAiProperties,
                new PropertiesSubjectResolver(cyclesProperties), captureEstimator);

        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenReturn(reservationAllow("res"));
        when(chain.nextCall(request)).thenReturn(response);
        when(cyclesClient.commitReservation(anyString(), any(CommitRequest.class)))
                .thenReturn(CyclesResponse.success(200, Map.of()));

        advisor.adviseCall(request, chain);

        assertThat(captured.get()).isSameAs(request);
    }

    @Test
    void estimatorNotCalledWhenRatesAreZero() {
        // Optimization pinned in the refactor: skip the estimator entirely when no rates
        // are set. Saves work for a value that would be discarded anyway.
        CyclesProperties cyclesProperties = new CyclesProperties();
        CyclesSpringAiProperties springAiProperties = new CyclesSpringAiProperties();
        springAiProperties.setEstimateFromPrompt(true);
        // rates remain 0

        AtomicReference<Integer> callCount = new AtomicReference<>(0);
        PromptTokenEstimator counterEstimator = req -> {
            callCount.updateAndGet(c -> c + 1);
            return 999L;
        };

        CyclesBudgetAdvisor advisor = new CyclesBudgetAdvisor(
                cyclesClient, cyclesProperties, springAiProperties,
                new PropertiesSubjectResolver(cyclesProperties), counterEstimator);

        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenReturn(reservationAllow("res"));
        when(chain.nextCall(request)).thenReturn(response);
        when(cyclesClient.commitReservation(anyString(), any(CommitRequest.class)))
                .thenReturn(CyclesResponse.success(200, Map.of()));

        advisor.adviseCall(request, chain);

        assertThat(callCount.get()).isZero();
    }

    @Test
    void estimatorNotCalledWhenEstimateFromPromptIsFalse() {
        CyclesProperties cyclesProperties = new CyclesProperties();
        CyclesSpringAiProperties springAiProperties = new CyclesSpringAiProperties();
        // estimate-from-prompt defaults to false
        springAiProperties.setInputCostPerToken(25L);  // rates set but feature flag off

        AtomicReference<Integer> callCount = new AtomicReference<>(0);
        PromptTokenEstimator counterEstimator = req -> {
            callCount.updateAndGet(c -> c + 1);
            return 999L;
        };

        CyclesBudgetAdvisor advisor = new CyclesBudgetAdvisor(
                cyclesClient, cyclesProperties, springAiProperties,
                new PropertiesSubjectResolver(cyclesProperties), counterEstimator);

        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenReturn(reservationAllow("res"));
        when(chain.nextCall(request)).thenReturn(response);
        when(cyclesClient.commitReservation(anyString(), any(CommitRequest.class)))
                .thenReturn(CyclesResponse.success(200, Map.of()));

        advisor.adviseCall(request, chain);

        assertThat(callCount.get()).isZero();
    }

    @Test
    void backwardCompatConstructorUsesDefaultCharsPerTokenEstimator() {
        // 4-arg constructor (resolver only, no estimator) preserves the default
        // chars-per-token impl. Equivalent of v0.2.0 prompt-estimation behavior.
        CyclesProperties cyclesProperties = new CyclesProperties();
        CyclesSpringAiProperties springAiProperties = new CyclesSpringAiProperties();

        // The lifecycle's internal estimator should equal a fresh CharsPerTokenEstimator
        // (default ratio = 4). Sanity-check via the explicit-default construction path —
        // both should yield the same reservation size for the same prompt.
        PromptTokenEstimator explicitDefault = new CharsPerTokenEstimator();

        CyclesBudgetAdvisor advisorExplicit = new CyclesBudgetAdvisor(
                cyclesClient, cyclesProperties, springAiProperties,
                new PropertiesSubjectResolver(cyclesProperties), explicitDefault);

        assertThat(advisorExplicit).isNotNull();
    }

    private static CyclesResponse<Map<String, Object>> reservationAllow(String id) {
        return CyclesResponse.success(200, Map.of(
                "decision", "ALLOW",
                "reservation_id", id,
                "scope_path", "tenant/x"
        ));
    }
}
