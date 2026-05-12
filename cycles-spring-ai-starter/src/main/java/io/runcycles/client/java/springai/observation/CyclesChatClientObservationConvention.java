package io.runcycles.client.java.springai.observation;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import io.runcycles.client.java.spring.config.CyclesProperties;
import io.runcycles.client.java.springai.autoconfigure.CyclesSpringAiProperties;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.observation.ChatClientObservationContext;
import org.springframework.ai.chat.client.observation.DefaultChatClientObservationConvention;

import java.util.Map;

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
 * <p>All values above are low-cardinality (small enumerable sets), so they are
 * suitable as metric dimensions and trace tags.
 *
 * <p>Additionally, when {@code cycles.spring-ai.emit-reservation-id-on-trace=true}
 * (the default), the convention also emits the active Cycles reservation id as a
 * <em>high-cardinality</em> KeyValue on each observation:
 *
 * <ul>
 *   <li>{@code cycles.reservation_id}</li>
 * </ul>
 *
 * <p>This enables trace ↔ Cycles reservation correlation in tracing backends. The
 * reservation id is high-cardinality (one unique value per chat call), so it's
 * intentionally kept off the low-cardinality metrics path. Set the property to false
 * to omit it (e.g. when your tracing backend charges by unique tag-value combinations).
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

    @Override
    public KeyValues getHighCardinalityKeyValues(ChatClientObservationContext context) {
        // Intentionally does NOT call super.getHighCardinalityKeyValues — Spring AI's
        // default implementation accesses several request fields (prompt(), options,
        // instructions) and can NPE if any are null. Our use case only cares about
        // the cycles.reservation_id KeyValue, so we emit just that. If Spring AI ever
        // adds high-cardinality defaults we care about, we can chain super back in
        // with appropriate null-guards.
        if (!springAiProperties.isEmitReservationIdOnTrace()) {
            return KeyValues.empty();
        }
        String reservationId = extractReservationId(context);
        if (reservationId == null) {
            return KeyValues.empty();
        }
        return KeyValues.of(KeyValue.of(CyclesObservationContextKeys.RESERVATION_ID, reservationId));
    }

    /**
     * Reads the reservation_id from the request's context map. The advisor stashes
     * it there after a successful reserve (see
     * {@link CyclesObservationContextKeys#RESERVATION_ID}). Returns null when no
     * reservation was attached — e.g. fail-open reserve skipped, or the user opted
     * out of observation-side reservation correlation.
     */
    private static String extractReservationId(ChatClientObservationContext context) {
        if (context == null) {
            return null;
        }
        ChatClientRequest request = context.getRequest();
        if (request == null) {
            return null;
        }
        Map<String, Object> ctx = request.context();
        if (ctx == null) {
            return null;
        }
        Object value = ctx.get(CyclesObservationContextKeys.RESERVATION_ID);
        return value == null ? null : value.toString();
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
