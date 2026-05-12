package io.runcycles.demo.springai;

import io.runcycles.client.java.spring.client.CyclesClient;
import io.runcycles.client.java.springai.advisor.CyclesBudgetAdvisor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test asserting the demo application's Spring context boots cleanly.
 *
 * <p>Pinned regression check: the demo previously failed to start because the
 * underlying {@code cycles-client-java-spring} auto-configuration requires
 * {@code cycles.base-url} and {@code cycles.api-key}, but only the
 * {@code cycles.spring-ai.*} properties were declared in {@code application.yml}.
 * If those properties go missing again, this test fails at context-load time
 * (matches the failure mode {@code mvn spring-boot:run} would surface).
 *
 * <p>The test also asserts that the Cycles + Spring AI wiring is end-to-end live:
 * a {@link CyclesClient}, a {@link CyclesBudgetAdvisor}, and an auto-configured
 * {@link ChatClient.Builder} all resolve from the context.
 */
@SpringBootTest
class DemoApplicationContextLoadsTest {

    @Autowired
    private CyclesClient cyclesClient;

    @Autowired
    private CyclesBudgetAdvisor cyclesBudgetAdvisor;

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @Test
    void cyclesAndSpringAiBeansAreWired() {
        assertThat(cyclesClient).isNotNull();
        assertThat(cyclesBudgetAdvisor).isNotNull();
        assertThat(chatClientBuilder).isNotNull();
    }
}
