package io.runcycles.client.java.springai.observation;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import io.runcycles.client.java.spring.config.CyclesProperties;
import io.runcycles.client.java.springai.autoconfigure.CyclesSpringAiProperties;
import org.springframework.ai.chat.client.observation.ChatClientObservationContext;
import org.springframework.ai.chat.client.observation.DefaultChatClientObservationConvention;

/**
 * Spring AI observation convention that augments chat-client traces with Cycles
 * attribution tags. Inherits Spring AI's default low-cardinality keys (request type,
 * advisor count, etc.) and appends the static attribution dimensions configured on
 * this app's Cycles starter:
 *
 * <ul>
 *   <li>{@code cycles.tenant}</li>
 *   <li>{@code cycles.workspace}</li>
 *   <li>{@code cycles.app}</li>
 *   <li>{@code cycles.action_kind}</li>
 *   <li>{@code cycles.action_name}</li>
 * </ul>
 *
 * <p>All values are low-cardinality (small enumerable sets), so they are suitable as
 * metric dimensions and trace tags. Per-call reservation IDs would be high-cardinality
 * and are intentionally NOT emitted here; that surface can be added in a future release
 * if there's demand for trace ↔ Cycles reservation correlation.
 *
 * <p>Opt-in by applying to a ChatClient via the builder, or by registering this bean
 * as the application's default convention:
 *
 * <pre>{@code
 * @Autowired CyclesChatClientObservationConvention cyclesConvention;
 *
 * ChatClient client = chatClientBuilder
 *     .observationConvention(cyclesConvention)
 *     .build();
 * }</pre>
 *
 * <p>The bean is auto-configured but NOT auto-attached to the ChatClient.Builder —
 * applying an observation convention has cross-cutting visibility implications that
 * should be a user decision rather than a default.
 */
public class CyclesChatClientObservationConvention extends DefaultChatClientObservationConvention {

    private final CyclesProperties cyclesProperties;
    private final CyclesSpringAiProperties springAiProperties;

    /**
     * Constructs the convention.
     *
     * @param cyclesProperties   SDK-level configuration (subject defaults).
     * @param springAiProperties Spring AI integration configuration.
     */
    public CyclesChatClientObservationConvention(CyclesProperties cyclesProperties,
                                                 CyclesSpringAiProperties springAiProperties) {
        this.cyclesProperties = cyclesProperties;
        this.springAiProperties = springAiProperties;
    }

    @Override
    public KeyValues getLowCardinalityKeyValues(ChatClientObservationContext context) {
        return super.getLowCardinalityKeyValues(context).and(cyclesKeyValues());
    }

    private KeyValues cyclesKeyValues() {
        return KeyValues.of(
                KeyValue.of("cycles.tenant",      nonNullOrUnknown(cyclesProperties.getTenant())),
                KeyValue.of("cycles.workspace",   nonNullOrUnknown(cyclesProperties.getWorkspace())),
                KeyValue.of("cycles.app",         nonNullOrUnknown(cyclesProperties.getApp())),
                KeyValue.of("cycles.action_kind", nonNullOrUnknown(springAiProperties.getActionKind())),
                KeyValue.of("cycles.action_name", nonNullOrUnknown(springAiProperties.getActionName()))
        );
    }

    private static String nonNullOrUnknown(String value) {
        return value == null ? "unknown" : value;
    }
}
