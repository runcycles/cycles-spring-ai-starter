package io.runcycles.client.java.springai;

import io.runcycles.client.java.springai.advisor.CyclesBudgetAdvisor;
import io.runcycles.client.java.springai.autoconfigure.CyclesSpringAiAutoConfiguration;
import io.runcycles.client.java.springai.autoconfigure.CyclesSpringAiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests for the Cycles Spring AI auto-configuration.
 *
 * <p>Asserts the property-binding and conditional-wiring contract. Behavioral coverage
 * for {@link CyclesBudgetAdvisor} is in {@link CyclesBudgetAdvisorTest}.
 */
class CyclesSpringAiAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CyclesSpringAiAutoConfiguration.class));

    @Test
    void wiresAdvisorWhenEnabledByDefault() {
        contextRunner
            .withPropertyValues("cycles.spring-ai.budget-id=test-budget")
            .run(ctx -> {
                assertThat(ctx).hasSingleBean(CyclesBudgetAdvisor.class);
                CyclesSpringAiProperties props = ctx.getBean(CyclesSpringAiProperties.class);
                assertThat(props.isEnabled()).isTrue();
                assertThat(props.getBudgetId()).isEqualTo("test-budget");
            });
    }

    @Test
    void doesNotWireWhenExplicitlyDisabled() {
        contextRunner
            .withPropertyValues("cycles.spring-ai.enabled=false")
            .run(ctx -> {
                assertThat(ctx).doesNotHaveBean(CyclesBudgetAdvisor.class);
            });
    }

    @Test
    void appliesPropertyDefaults() {
        contextRunner
            .run(ctx -> {
                CyclesSpringAiProperties props = ctx.getBean(CyclesSpringAiProperties.class);
                assertThat(props.isEnabled()).isTrue();
                assertThat(props.getServerUrl()).isEqualTo("http://localhost:8080");
                assertThat(props.isFailOpen()).isFalse();
                assertThat(props.getBudgetId()).isNull();
            });
    }

    @Test
    void bindsAllPropertiesWhenSet() {
        contextRunner
            .withPropertyValues(
                "cycles.spring-ai.enabled=true",
                "cycles.spring-ai.budget-id=tenant-prod",
                "cycles.spring-ai.server-url=https://cycles.example.com",
                "cycles.spring-ai.fail-open=true"
            )
            .run(ctx -> {
                CyclesSpringAiProperties props = ctx.getBean(CyclesSpringAiProperties.class);
                assertThat(props.isEnabled()).isTrue();
                assertThat(props.getBudgetId()).isEqualTo("tenant-prod");
                assertThat(props.getServerUrl()).isEqualTo("https://cycles.example.com");
                assertThat(props.isFailOpen()).isTrue();
            });
    }
}
