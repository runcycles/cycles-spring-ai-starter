package io.runcycles.client.java.springai;

import io.runcycles.client.java.spring.client.CyclesClient;
import io.runcycles.client.java.spring.config.CyclesProperties;
import io.runcycles.client.java.springai.advisor.CyclesBudgetAdvisor;
import io.runcycles.client.java.springai.autoconfigure.CyclesSpringAiAutoConfiguration;
import io.runcycles.client.java.springai.autoconfigure.CyclesSpringAiProperties;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClientCustomizer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests for the Cycles Spring AI auto-configuration.
 *
 * <p>Asserts the conditional-wiring contract and property binding. Behavioral coverage
 * for {@link CyclesBudgetAdvisor} is in {@link CyclesBudgetAdvisorTest}.
 *
 * <p>The {@link CyclesClient} bean is provided by the underlying
 * {@code cycles-client-java-spring} auto-configuration in real applications; here it's
 * stubbed so the {@code @ConditionalOnBean(CyclesClient.class)} matches.
 */
class CyclesSpringAiAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CyclesSpringAiAutoConfiguration.class))
            .withBean(CyclesClient.class, () -> Mockito.mock(CyclesClient.class))
            .withBean(CyclesProperties.class, CyclesProperties::new);

    @Test
    void wiresAdvisorAndCustomizerWhenEnabledByDefault() {
        contextRunner.run(ctx -> {
            assertThat(ctx).hasSingleBean(CyclesBudgetAdvisor.class);
            assertThat(ctx).hasSingleBean(ChatClientCustomizer.class);
            CyclesSpringAiProperties props = ctx.getBean(CyclesSpringAiProperties.class);
            assertThat(props.isEnabled()).isTrue();
        });
    }

    @Test
    void doesNotWireWhenExplicitlyDisabled() {
        contextRunner
                .withPropertyValues("cycles.spring-ai.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(CyclesBudgetAdvisor.class);
                    assertThat(ctx).doesNotHaveBean(ChatClientCustomizer.class);
                });
    }

    @Test
    void doesNotWireWithoutCyclesClientBean() {
        // No CyclesClient → @ConditionalOnBean fails → advisor not registered.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CyclesSpringAiAutoConfiguration.class))
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(CyclesBudgetAdvisor.class);
                    assertThat(ctx).doesNotHaveBean(ChatClientCustomizer.class);
                });
    }

    @Test
    void appliesPropertyDefaults() {
        contextRunner.run(ctx -> {
            CyclesSpringAiProperties props = ctx.getBean(CyclesSpringAiProperties.class);
            assertThat(props.isEnabled()).isTrue();
            assertThat(props.isFailOpen()).isFalse();
            assertThat(props.getDefaultEstimate()).isEqualTo(1000L);
            assertThat(props.getEstimateUnit()).isEqualTo("USD_MICROCENTS");
            assertThat(props.getActionKind()).isEqualTo("llm.chat");
            assertThat(props.getActionName()).isEqualTo("spring-ai-chat");
        });
    }

    @Test
    void bindsAllPropertiesWhenSet() {
        contextRunner
                .withPropertyValues(
                        "cycles.spring-ai.enabled=true",
                        "cycles.spring-ai.default-estimate=5000",
                        "cycles.spring-ai.estimate-unit=TOKENS",
                        "cycles.spring-ai.action-kind=llm.completion",
                        "cycles.spring-ai.action-name=gpt-4o",
                        "cycles.spring-ai.fail-open=true"
                )
                .run(ctx -> {
                    CyclesSpringAiProperties props = ctx.getBean(CyclesSpringAiProperties.class);
                    assertThat(props.isEnabled()).isTrue();
                    assertThat(props.isFailOpen()).isTrue();
                    assertThat(props.getDefaultEstimate()).isEqualTo(5000L);
                    assertThat(props.getEstimateUnit()).isEqualTo("TOKENS");
                    assertThat(props.getActionKind()).isEqualTo("llm.completion");
                    assertThat(props.getActionName()).isEqualTo("gpt-4o");
                });
    }
}
