package io.runcycles.client.java.springai.autoconfigure;

import io.runcycles.client.java.spring.autoconfigure.CyclesAutoConfiguration;
import io.runcycles.client.java.spring.client.CyclesClient;
import io.runcycles.client.java.spring.config.CyclesProperties;
import io.runcycles.client.java.springai.advisor.CyclesBudgetAdvisor;
import io.runcycles.client.java.springai.advisor.CyclesBudgetStreamAdvisor;
import io.runcycles.client.java.springai.tool.CyclesToolGate;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the Cycles Spring AI integration.
 *
 * <p>Registers when Spring AI's {@link ChatClient} is on the classpath, the underlying
 * {@link CyclesClient} bean exists (provided by {@code cycles-client-java-spring}'s
 * auto-configuration), and {@code cycles.spring-ai.enabled} is true (default).
 *
 * <p>{@link AutoConfigureAfter} guarantees Spring evaluates this auto-config *after*
 * {@link CyclesAutoConfiguration} has run, so the {@link ConditionalOnBean} check
 * against {@link CyclesClient} sees the bean that the SDK registers. Without this
 * ordering hint, auto-config evaluation order is non-deterministic and the integration
 * could silently fail to wire on some classpath orderings.
 *
 * <p>Wires two beans:
 * <ul>
 *   <li>{@link CyclesBudgetAdvisor} — the call advisor itself.</li>
 *   <li>{@link ChatClientCustomizer} — applies the advisor to the auto-configured
 *       {@link ChatClient.Builder} via {@code builder.defaultAdvisors(advisor)}. Spring AI's
 *       {@code ChatClientAutoConfiguration} discovers customizer beans and invokes them
 *       on the builder it creates, which is how the advisor actually gets attached to every
 *       {@code ChatClient} produced from that builder. Registering only the {@code CallAdvisor}
 *       bean does NOT attach it — Spring AI does not auto-discover advisors.</li>
 * </ul>
 */
@AutoConfiguration
@AutoConfigureAfter(CyclesAutoConfiguration.class)
@ConditionalOnClass({ ChatClient.class, ChatClientCustomizer.class })
@ConditionalOnBean(CyclesClient.class)
@ConditionalOnProperty(prefix = "cycles.spring-ai", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(CyclesSpringAiProperties.class)
public class CyclesSpringAiAutoConfiguration {

    /** Default constructor used by Spring to instantiate the auto-configuration. */
    public CyclesSpringAiAutoConfiguration() {
        // Annotation-driven; nothing to initialize.
    }

    /**
     * Creates the Cycles budget advisor bean.
     *
     * <p>{@link ConditionalOnMissingBean} causes this auto-configured advisor to back off
     * when the application provides its own {@code CyclesBudgetAdvisor} bean — e.g. a
     * subclass with custom subject resolution. Standard Spring Boot auto-configuration
     * etiquette: defaults yield to user-provided beans.
     *
     * @param cyclesClient        the Cycles HTTP client.
     * @param cyclesProperties    SDK-level configuration (subject defaults).
     * @param springAiProperties  Spring AI integration configuration.
     * @return the budget-gating call advisor.
     */
    @Bean
    @ConditionalOnMissingBean
    public CyclesBudgetAdvisor cyclesBudgetAdvisor(CyclesClient cyclesClient,
                                                   CyclesProperties cyclesProperties,
                                                   CyclesSpringAiProperties springAiProperties) {
        return new CyclesBudgetAdvisor(cyclesClient, cyclesProperties, springAiProperties);
    }

    /**
     * Creates the Cycles streaming budget advisor bean.
     *
     * <p>Companion to {@link #cyclesBudgetAdvisor} that handles streaming
     * {@code chatClient.prompt(...).stream()} invocations. Same conditional-on-missing
     * semantics — user-provided beans take precedence.
     *
     * @param cyclesClient        the Cycles HTTP client.
     * @param cyclesProperties    SDK-level configuration.
     * @param springAiProperties  Spring AI integration configuration.
     * @return the streaming budget-gating advisor.
     */
    @Bean
    @ConditionalOnMissingBean
    public CyclesBudgetStreamAdvisor cyclesBudgetStreamAdvisor(CyclesClient cyclesClient,
                                                               CyclesProperties cyclesProperties,
                                                               CyclesSpringAiProperties springAiProperties) {
        return new CyclesBudgetStreamAdvisor(cyclesClient, cyclesProperties, springAiProperties);
    }

    /**
     * Registers both advisors on the auto-configured {@link ChatClient.Builder}.
     *
     * <p>Spring AI applies all {@link ChatClientCustomizer} beans to the builder it
     * creates; this customizer calls {@code defaultAdvisors(...)} with both the call
     * and stream advisors, so every {@code ChatClient} built from the auto-configured
     * builder gates both non-streaming ({@code .call()}) and streaming ({@code .stream()})
     * invocations through Cycles.
     *
     * <p>{@link ConditionalOnMissingBean} is keyed by <em>name</em> (not type) here:
     * other Spring AI customizers in the context (chat memory, prompt augmentation, etc.)
     * are additive, so we must NOT back off just because some other ChatClientCustomizer
     * exists. A user replacing the Cycles attachment specifically can override this bean
     * by declaring their own bean with the name {@code cyclesChatClientCustomizer}.
     *
     * @param callAdvisor   the non-streaming budget advisor.
     * @param streamAdvisor the streaming budget advisor.
     * @return a customizer that attaches both advisors as defaults on every ChatClient.
     */
    @Bean
    @ConditionalOnMissingBean(name = "cyclesChatClientCustomizer")
    public ChatClientCustomizer cyclesChatClientCustomizer(CyclesBudgetAdvisor callAdvisor,
                                                           CyclesBudgetStreamAdvisor streamAdvisor) {
        return builder -> builder.defaultAdvisors(callAdvisor, streamAdvisor);
    }

    /**
     * Tool-callback gate factory bean. Lets users opt into Cycles gating on tool
     * invocations by calling {@code cyclesToolGate.wrap(myTool)} where they construct
     * their tools.
     *
     * <p>Unlike the chat advisors, tool gating is not auto-applied — Spring AI does not
     * provide a hook to intercept every tool registration, so users explicitly choose
     * which tools to gate.
     *
     * @param cyclesClient       the Cycles HTTP client.
     * @param cyclesProperties   SDK-level configuration.
     * @param springAiProperties Spring AI integration configuration.
     * @return the tool gate factory.
     */
    @Bean
    @ConditionalOnMissingBean
    public CyclesToolGate cyclesToolGate(CyclesClient cyclesClient,
                                         CyclesProperties cyclesProperties,
                                         CyclesSpringAiProperties springAiProperties) {
        return new CyclesToolGate(cyclesClient, cyclesProperties, springAiProperties);
    }
}
