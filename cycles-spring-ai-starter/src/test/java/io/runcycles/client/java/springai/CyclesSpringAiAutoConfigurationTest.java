package io.runcycles.client.java.springai;

import io.runcycles.client.java.spring.client.CyclesClient;
import io.runcycles.client.java.spring.config.CyclesProperties;
import io.runcycles.client.java.springai.advisor.CyclesBudgetAdvisor;
import io.runcycles.client.java.springai.advisor.CyclesBudgetStreamAdvisor;
import io.runcycles.client.java.springai.autoconfigure.CyclesSpringAiAutoConfiguration;
import io.runcycles.client.java.springai.autoconfigure.CyclesSpringAiProperties;
import io.runcycles.client.java.springai.observation.CyclesChatClientObservationConvention;
import io.runcycles.client.java.springai.subject.PropertiesSubjectResolver;
import io.runcycles.client.java.springai.subject.SubjectResolver;
import io.runcycles.client.java.springai.tokenizer.CharsPerTokenEstimator;
import io.runcycles.client.java.springai.tokenizer.JtokkitPromptTokenEstimator;
import io.runcycles.client.java.springai.tokenizer.PromptTokenEstimator;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientCustomizer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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
            assertThat(ctx).hasSingleBean(CyclesBudgetStreamAdvisor.class);
            assertThat(ctx).hasSingleBean(ChatClientCustomizer.class);
            assertThat(ctx).hasSingleBean(CyclesChatClientObservationConvention.class);
            assertThat(ctx).hasSingleBean(SubjectResolver.class);
            assertThat(ctx.getBean(SubjectResolver.class)).isInstanceOf(PropertiesSubjectResolver.class);
            CyclesSpringAiProperties props = ctx.getBean(CyclesSpringAiProperties.class);
            assertThat(props.isEnabled()).isTrue();
        });
    }

    @Test
    void wiresDefaultPromptTokenEstimator() {
        contextRunner.run(ctx -> {
            assertThat(ctx).hasSingleBean(PromptTokenEstimator.class);
            assertThat(ctx.getBean(PromptTokenEstimator.class)).isInstanceOf(CharsPerTokenEstimator.class);
        });
    }

    @Test
    void wiresJtokkitEstimatorWhenEncodingPropertySet() {
        // Property set + jtokkit on test classpath -> JtokkitPromptTokenEstimator wins
        // over the chars-per-token default. Bean is still injected as PromptTokenEstimator.
        contextRunner
                .withPropertyValues("cycles.spring-ai.token-estimator-encoding=cl100k_base")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(PromptTokenEstimator.class);
                    assertThat(ctx.getBean(PromptTokenEstimator.class))
                            .isInstanceOf(JtokkitPromptTokenEstimator.class);
                });
    }

    @Test
    void invalidEncodingPropertyFailsBeanInitializationAtStartup() {
        // An unknown encoding name should surface as a bean-initialization failure at
        // startup (not as a silently-wrong estimator at first call). JtokkitPromptTokenEstimator
        // throws IllegalArgumentException from its constructor; Spring wraps that and
        // ApplicationContext fails to start.
        contextRunner
                .withPropertyValues("cycles.spring-ai.token-estimator-encoding=not_a_real_encoding")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("Unknown jtokkit encoding");
                });
    }

    @Test
    void emptyEncodingPropertyFallsBackToCharsPerToken() {
        // An empty string for the encoding (e.g. cleared in config) should be treated
        // the same as unset — chars/4 default, no startup failure.
        contextRunner
                .withPropertyValues("cycles.spring-ai.token-estimator-encoding=")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(PromptTokenEstimator.class);
                    assertThat(ctx.getBean(PromptTokenEstimator.class))
                            .isInstanceOf(CharsPerTokenEstimator.class);
                });
    }

    @Test
    void userProvidedTokenEstimatorOverridesDefault() {
        PromptTokenEstimator userEstimator = req -> 42L;
        contextRunner
                .withBean("userTokenEstimator", PromptTokenEstimator.class, () -> userEstimator)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(PromptTokenEstimator.class);
                    assertThat(ctx.getBean(PromptTokenEstimator.class)).isSameAs(userEstimator);
                    // Advisors still wire — they just take the user's estimator.
                    assertThat(ctx).hasSingleBean(CyclesBudgetAdvisor.class);
                    assertThat(ctx).hasSingleBean(CyclesBudgetStreamAdvisor.class);
                });
    }

    @Test
    void userProvidedSubjectResolverOverridesDefault() {
        // User registers a custom resolver. The auto-configured default backs off via
        // @ConditionalOnMissingBean. Downstream beans (advisor / stream advisor /
        // tool gate) wire to the user's resolver.
        SubjectResolver userResolver = req -> null;
        contextRunner
                .withBean("userSubjectResolver", SubjectResolver.class, () -> userResolver)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(SubjectResolver.class);
                    assertThat(ctx.getBean(SubjectResolver.class)).isSameAs(userResolver);
                    // Advisors still wire — they just take the user's resolver.
                    assertThat(ctx).hasSingleBean(CyclesBudgetAdvisor.class);
                    assertThat(ctx).hasSingleBean(CyclesBudgetStreamAdvisor.class);
                });
    }

    @Test
    void doesNotWireWhenExplicitlyDisabled() {
        contextRunner
                .withPropertyValues("cycles.spring-ai.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(CyclesBudgetAdvisor.class);
                    assertThat(ctx).doesNotHaveBean(CyclesBudgetStreamAdvisor.class);
                    assertThat(ctx).doesNotHaveBean(ChatClientCustomizer.class);
                });
    }

    @Test
    void doesNotWireWithoutCyclesClientBean() {
        // No CyclesClient → @ConditionalOnBean fails → advisors not registered.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CyclesSpringAiAutoConfiguration.class))
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(CyclesBudgetAdvisor.class);
                    assertThat(ctx).doesNotHaveBean(CyclesBudgetStreamAdvisor.class);
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
                        "cycles.spring-ai.fail-open=true",
                        "cycles.spring-ai.estimate-from-prompt=true",
                        "cycles.spring-ai.tool-action-kind=tool.exec",
                        "cycles.spring-ai.tool-action-name-prefix=my-tool:"
                )
                .run(ctx -> {
                    CyclesSpringAiProperties props = ctx.getBean(CyclesSpringAiProperties.class);
                    assertThat(props.isEnabled()).isTrue();
                    assertThat(props.isFailOpen()).isTrue();
                    assertThat(props.getDefaultEstimate()).isEqualTo(5000L);
                    assertThat(props.getEstimateUnit()).isEqualTo("TOKENS");
                    assertThat(props.getActionKind()).isEqualTo("llm.completion");
                    assertThat(props.getActionName()).isEqualTo("gpt-4o");
                    assertThat(props.isEstimateFromPrompt()).isTrue();
                    assertThat(props.getToolActionKind()).isEqualTo("tool.exec");
                    assertThat(props.getToolActionNamePrefix()).isEqualTo("my-tool:");
                });
    }

    @Test
    void customizerAttachesBothCallAndStreamAdvisorsToChatClientBuilder() {
        // Exercise the lambda body of cyclesChatClientCustomizer — verify it actually
        // calls builder.defaultAdvisors(callAdvisor, streamAdvisor). Without this test
        // the lambda is registered as a bean but never invoked, leaving its body
        // uncovered (and a subtle regression risk if the wiring shape changes).
        contextRunner.run(ctx -> {
            ChatClientCustomizer customizer = ctx.getBean("cyclesChatClientCustomizer", ChatClientCustomizer.class);
            CyclesBudgetAdvisor callAdvisor = ctx.getBean(CyclesBudgetAdvisor.class);
            CyclesBudgetStreamAdvisor streamAdvisor = ctx.getBean(CyclesBudgetStreamAdvisor.class);
            ChatClient.Builder builder = mock(ChatClient.Builder.class);

            customizer.customize(builder);

            verify(builder).defaultAdvisors(callAdvisor, streamAdvisor);
        });
    }

    @Test
    void userProvidedAdvisorOverridesAutoConfigured() {
        // App supplies its own CyclesBudgetAdvisor (e.g. subclassed for custom subject
        // resolution). Auto-config must back off — exactly ONE advisor in the context,
        // and it must be the user's. The customizer should pick up the user's advisor.
        CyclesBudgetAdvisor userAdvisor = Mockito.mock(CyclesBudgetAdvisor.class);
        contextRunner
                .withBean("userCyclesBudgetAdvisor", CyclesBudgetAdvisor.class, () -> userAdvisor)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(CyclesBudgetAdvisor.class);
                    assertThat(ctx.getBean(CyclesBudgetAdvisor.class)).isSameAs(userAdvisor);
                    // Customizer still wires (it depends on the now-user-supplied advisor).
                    assertThat(ctx).hasBean("cyclesChatClientCustomizer");
                });
    }

    @Test
    void userProvidedStreamAdvisorOverridesAutoConfigured() {
        // Symmetric to the call-advisor override — user-supplied stream advisor takes
        // precedence and the customizer picks it up.
        CyclesBudgetStreamAdvisor userStreamAdvisor = Mockito.mock(CyclesBudgetStreamAdvisor.class);
        contextRunner
                .withBean("userCyclesBudgetStreamAdvisor", CyclesBudgetStreamAdvisor.class, () -> userStreamAdvisor)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(CyclesBudgetStreamAdvisor.class);
                    assertThat(ctx.getBean(CyclesBudgetStreamAdvisor.class)).isSameAs(userStreamAdvisor);
                    assertThat(ctx).hasBean("cyclesChatClientCustomizer");
                });
    }

    @Test
    void rejectsNegativeInputCostPerTokenAtBindingTime() {
        contextRunner
                .withPropertyValues("cycles.spring-ai.input-cost-per-token=-1")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("input-cost-per-token must be non-negative");
                });
    }

    @Test
    void rejectsNegativeOutputCostPerTokenAtBindingTime() {
        contextRunner
                .withPropertyValues("cycles.spring-ai.output-cost-per-token=-5")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("output-cost-per-token must be non-negative");
                });
    }

    @Test
    void bindsCostPerTokenProperties() {
        contextRunner
                .withPropertyValues(
                        "cycles.spring-ai.input-cost-per-token=25",
                        "cycles.spring-ai.output-cost-per-token=100"
                )
                .run(ctx -> {
                    CyclesSpringAiProperties props = ctx.getBean(CyclesSpringAiProperties.class);
                    assertThat(props.getInputCostPerToken()).isEqualTo(25L);
                    assertThat(props.getOutputCostPerToken()).isEqualTo(100L);
                });
    }

    @Test
    void rejectsNegativeDefaultEstimateAtBindingTime() {
        // A negative estimate is either rejected by Cycles as malformed or — worse —
        // silently treated as a budget increase that subverts the authority gate.
        // Fail fast at config-binding time so the operator sees the misconfiguration
        // at app startup, not at first chat call.
        contextRunner
                .withPropertyValues("cycles.spring-ai.default-estimate=-100")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("default-estimate must be non-negative")
                            .hasMessageContaining("-100");
                });
    }

    @Test
    void acceptsZeroDefaultEstimate() {
        // Zero is a legitimate value (e.g. a dry-run or accounting-only mode where the
        // reservation should not actually charge). Must NOT be rejected.
        contextRunner
                .withPropertyValues("cycles.spring-ai.default-estimate=0")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(CyclesSpringAiProperties.class).getDefaultEstimate())
                            .isZero();
                });
    }

    @Test
    void userProvidedCustomizerOverridesAutoConfigured() {
        // App supplies its own cyclesChatClientCustomizer to control attachment behavior
        // (e.g. different advisor ordering or additional advisor stacking). Name-based
        // ConditionalOnMissingBean means our customizer backs off only when a same-named
        // bean exists — not when other ChatClientCustomizer beans are present.
        ChatClientCustomizer userCustomizer = builder -> { /* user's wiring */ };
        contextRunner
                .withBean("cyclesChatClientCustomizer", ChatClientCustomizer.class, () -> userCustomizer)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(ChatClientCustomizer.class);
                    assertThat(ctx.getBean("cyclesChatClientCustomizer", ChatClientCustomizer.class))
                            .isSameAs(userCustomizer);
                    // Auto-configured advisor still wires; only the customizer was overridden.
                    assertThat(ctx).hasSingleBean(CyclesBudgetAdvisor.class);
                });
    }
}
