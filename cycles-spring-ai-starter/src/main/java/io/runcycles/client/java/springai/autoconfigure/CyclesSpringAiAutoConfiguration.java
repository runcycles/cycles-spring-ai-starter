package io.runcycles.client.java.springai.autoconfigure;

import io.runcycles.client.java.spring.autoconfigure.CyclesAutoConfiguration;
import io.runcycles.client.java.spring.client.CyclesClient;
import io.runcycles.client.java.spring.config.CyclesProperties;
import io.runcycles.client.java.springai.advisor.CyclesBudgetAdvisor;
import io.runcycles.client.java.springai.advisor.CyclesBudgetStreamAdvisor;
import io.runcycles.client.java.springai.observation.CyclesChatClientObservationConvention;
import io.runcycles.client.java.springai.subject.PropertiesSubjectResolver;
import io.runcycles.client.java.springai.subject.SubjectResolver;
import io.runcycles.client.java.springai.tokenizer.CharsPerTokenEstimator;
import io.runcycles.client.java.springai.tokenizer.JtokkitPromptTokenEstimator;
import io.runcycles.client.java.springai.tokenizer.PromptTokenEstimator;
import io.runcycles.client.java.springai.tool.CyclesToolGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.ClassUtils;
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

    private static final Logger log = LoggerFactory.getLogger(CyclesSpringAiAutoConfiguration.class);

    private static final String JTOKKIT_ENCODINGS_CLASS = "com.knuddels.jtokkit.Encodings";

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
    /**
     * Default {@link SubjectResolver} bean — reads tenant/workspace/app from
     * {@link CyclesProperties} on every call (v0.1.0 / v0.2.0 behavior).
     *
     * <p>Multi-tenant agents typically supply their own {@code SubjectResolver} bean
     * to route attribution per request (e.g. tenant from an authenticated principal,
     * a request header, or a thread-local). {@link ConditionalOnMissingBean} backs
     * this default off when a user bean is registered.
     *
     * @param cyclesProperties SDK-level configuration carrying the subject defaults.
     * @return the default property-derived resolver.
     */
    @Bean
    @ConditionalOnMissingBean
    public SubjectResolver cyclesSubjectResolver(CyclesProperties cyclesProperties) {
        return new PropertiesSubjectResolver(cyclesProperties);
    }

    /**
     * Default {@link PromptTokenEstimator} bean.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>If {@code cycles.spring-ai.token-estimator-encoding} is set AND jtokkit
     *       is on the classpath, register a {@link JtokkitPromptTokenEstimator} with
     *       the configured encoding — real BPE tokenization, significantly more
     *       accurate than the chars/4 heuristic.</li>
     *   <li>If the property is set BUT jtokkit isn't on the classpath: log a WARN
     *       at startup and fall back to {@link CharsPerTokenEstimator} (the chars/4
     *       heuristic). Users see this immediately at boot rather than getting
     *       silently-wrong estimates at first call.</li>
     *   <li>Otherwise: register {@link CharsPerTokenEstimator} (v0.2.0 default).</li>
     * </ol>
     *
     * <p>{@link ConditionalOnMissingBean} backs this off when a user-provided
     * {@code PromptTokenEstimator} bean is registered.
     *
     * @param springAiProperties Spring AI integration configuration.
     * @return the resolved estimator.
     */
    @Bean
    @ConditionalOnMissingBean
    public PromptTokenEstimator cyclesPromptTokenEstimator(CyclesSpringAiProperties springAiProperties) {
        String encoding = springAiProperties.getTokenEstimatorEncoding();
        if (encoding == null || encoding.isBlank()) {
            return new CharsPerTokenEstimator();
        }
        if (!ClassUtils.isPresent(JTOKKIT_ENCODINGS_CLASS, getClass().getClassLoader())) {
            log.warn("cycles.spring-ai.token-estimator-encoding is set to '{}' but jtokkit "
                    + "is not on the classpath. Add com.knuddels:jtokkit:1.1.0 (or supply "
                    + "your own PromptTokenEstimator bean) to enable real BPE tokenization. "
                    + "Falling back to the chars/4 heuristic for now.", encoding);
            return new CharsPerTokenEstimator();
        }
        return new JtokkitPromptTokenEstimator(encoding);
    }

    /**
     * Creates the Cycles non-streaming budget advisor bean.
     *
     * <p>{@link ConditionalOnMissingBean} causes this auto-configured advisor to back off
     * when the application provides its own {@code CyclesBudgetAdvisor} bean — e.g. a
     * subclass with custom reserve/commit semantics. Standard Spring Boot
     * auto-configuration etiquette: defaults yield to user-provided beans.
     *
     * @param cyclesClient        the Cycles HTTP client.
     * @param cyclesProperties    SDK-level configuration.
     * @param springAiProperties  Spring AI integration configuration.
     * @param subjectResolver     resolves the Cycles subject per reservation.
     * @param tokenEstimator      estimates prompt tokens for prompt-based reservation sizing.
     * @return the budget-gating call advisor.
     */
    @Bean
    @ConditionalOnMissingBean
    public CyclesBudgetAdvisor cyclesBudgetAdvisor(CyclesClient cyclesClient,
                                                   CyclesProperties cyclesProperties,
                                                   CyclesSpringAiProperties springAiProperties,
                                                   SubjectResolver subjectResolver,
                                                   PromptTokenEstimator tokenEstimator) {
        return new CyclesBudgetAdvisor(cyclesClient, cyclesProperties, springAiProperties,
                subjectResolver, tokenEstimator);
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
     * @param subjectResolver     resolves the Cycles subject per reservation.
     * @param tokenEstimator      estimates prompt tokens for prompt-based reservation sizing.
     * @return the streaming budget-gating advisor.
     */
    @Bean
    @ConditionalOnMissingBean
    public CyclesBudgetStreamAdvisor cyclesBudgetStreamAdvisor(CyclesClient cyclesClient,
                                                               CyclesProperties cyclesProperties,
                                                               CyclesSpringAiProperties springAiProperties,
                                                               SubjectResolver subjectResolver,
                                                               PromptTokenEstimator tokenEstimator) {
        return new CyclesBudgetStreamAdvisor(cyclesClient, cyclesProperties, springAiProperties,
                subjectResolver, tokenEstimator);
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
     * @param subjectResolver    resolves the Cycles subject for each tool reservation.
     *                           Invoked with {@code null} for the {@code ChatClientRequest}
     *                           parameter because tool callbacks don't carry one.
     * @return the tool gate factory.
     */
    @Bean
    @ConditionalOnMissingBean
    public CyclesToolGate cyclesToolGate(CyclesClient cyclesClient,
                                         CyclesProperties cyclesProperties,
                                         CyclesSpringAiProperties springAiProperties,
                                         SubjectResolver subjectResolver) {
        return new CyclesToolGate(cyclesClient, cyclesProperties, springAiProperties, subjectResolver);
    }

    /**
     * Cycles-aware observation convention bean for chat-client traces. Adds
     * low-cardinality attribution tags (tenant, workspace, app, action kind/name)
     * to Spring AI ChatClient observations. NOT auto-attached to ChatClient.Builder —
     * applying it is a user decision (cross-cutting trace visibility).
     *
     * @param cyclesProperties   SDK-level configuration.
     * @param springAiProperties Spring AI integration configuration.
     * @return the convention bean.
     */
    @Bean
    @ConditionalOnMissingBean
    public CyclesChatClientObservationConvention cyclesChatClientObservationConvention(
            CyclesProperties cyclesProperties,
            CyclesSpringAiProperties springAiProperties) {
        return new CyclesChatClientObservationConvention(cyclesProperties, springAiProperties);
    }
}
