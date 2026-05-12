package io.runcycles.client.java.springai.autoconfigure;

import io.runcycles.client.java.springai.advisor.CyclesBudgetAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the Cycles Spring AI integration.
 *
 * <p>Registers when Spring AI's {@code ChatClient} is on the classpath and
 * {@code cycles.spring-ai.enabled} is true (default).
 */
@AutoConfiguration
@ConditionalOnClass(ChatClient.class)
@ConditionalOnProperty(prefix = "cycles.spring-ai", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(CyclesSpringAiProperties.class)
public class CyclesSpringAiAutoConfiguration {

    /** Default constructor used by Spring to instantiate the auto-configuration. */
    public CyclesSpringAiAutoConfiguration() {
        // Annotation-driven; nothing to initialize.
    }

    /**
     * Registers the Cycles budget advisor as a Spring AI {@link CallAdvisor}.
     *
     * <p>Spring AI's auto-configured {@code ChatClient.Builder} picks up all {@link CallAdvisor}
     * beans from the context and registers them on every produced ChatClient.
     *
     * @param properties the bound Cycles properties.
     * @return the budget-gating call advisor.
     */
    @Bean
    public CallAdvisor cyclesBudgetAdvisor(CyclesSpringAiProperties properties) {
        return new CyclesBudgetAdvisor(properties);
    }
}
