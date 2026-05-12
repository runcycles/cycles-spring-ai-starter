package io.runcycles.client.java.springai;

import io.runcycles.client.java.springai.advisor.CyclesBudgetAdvisor;
import io.runcycles.client.java.springai.autoconfigure.CyclesSpringAiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.core.Ordered;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CyclesBudgetAdvisor}.
 *
 * <p>At scaffold stage the advisor is a pass-through to the chain; these tests pin the
 * pass-through contract so the next implementer adds budget logic without accidentally
 * changing the chain semantics.
 */
@ExtendWith(MockitoExtension.class)
class CyclesBudgetAdvisorTest {

    @Mock CallAdvisorChain chain;
    @Mock ChatClientRequest request;
    @Mock ChatClientResponse response;

    private CyclesBudgetAdvisor advisor;

    @BeforeEach
    void setUp() {
        CyclesSpringAiProperties properties = new CyclesSpringAiProperties();
        properties.setBudgetId("test-budget");
        advisor = new CyclesBudgetAdvisor(properties);
    }

    @Test
    void nameIsCyclesBudget() {
        assertThat(advisor.getName()).isEqualTo("cycles-budget");
    }

    @Test
    void orderRunsEarlyEnoughToGateCost() {
        // Should run before any cost-incurring downstream advisor, so order must be near
        // HIGHEST_PRECEDENCE. The exact offset is implementation detail; assert direction.
        assertThat(advisor.getOrder()).isLessThan(Ordered.LOWEST_PRECEDENCE);
        assertThat(advisor.getOrder()).isCloseTo(Ordered.HIGHEST_PRECEDENCE, org.assertj.core.data.Offset.offset(1000));
    }

    @Test
    void adviseCallPassesThroughToChain() {
        when(chain.nextCall(request)).thenReturn(response);

        ChatClientResponse result = advisor.adviseCall(request, chain);

        assertThat(result).isSameAs(response);
        verify(chain).nextCall(request);
    }
}
