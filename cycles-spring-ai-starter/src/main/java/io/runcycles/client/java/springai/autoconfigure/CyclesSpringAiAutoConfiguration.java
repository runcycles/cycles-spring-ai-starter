package io.runcycles.client.java.springai.autoconfigure;

import io.runcycles.client.java.spring.client.CyclesClient;
import io.runcycles.client.java.spring.config.CyclesProperties;
import io.runcycles.client.java.springai.advisor.CyclesBudgetAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
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
     * @param cyclesClient        the Cycles HTTP client.
     * @param cyclesProperties    SDK-level configuration (subject defaults).
     * @param springAiProperties  Spring AI integration configuration.
     * @return the budget-gating call advisor.
     */
    @Bean
    public CyclesBudgetAdvisor cyclesBudgetAdvisor(CyclesClient cyclesClient,
                                                   CyclesProperties cyclesProperties,
                                                   CyclesSpringAiProperties springAiProperties) {
        return new CyclesBudgetAdvisor(cyclesClient, cyclesProperties, springAiProperties);
    }

    /**
     * Registers the advisor on the auto-configured {@link ChatClient.Builder}.
     *
     * <p>Spring AI applies all {@link ChatClientCustomizer} beans to the builder it
     * creates; this customizer calls {@code defaultAdvisors(...)} so every
     * {@code ChatClient} built from the auto-configured builder gates calls through
     * Cycles.
     *
     * @param advisor the budget advisor from {@link #cyclesBudgetAdvisor}.
     * @return a customizer that attaches the advisor as a default advisor on every ChatClient.
     */
    @Bean
    public ChatClientCustomizer cyclesChatClientCustomizer(CyclesBudgetAdvisor advisor) {
        return builder -> builder.defaultAdvisors(advisor);
    }
}
