package io.runcycles.client.java.springai.observation;

/**
 * Well-known keys used to thread Cycles state through the
 * {@link org.springframework.ai.chat.client.ChatClientRequest#context()} map so the
 * {@link CyclesChatClientObservationConvention} can emit it as KeyValues on each
 * chat-client trace.
 *
 * <p>Advisors write into the context map after a successful operation; the
 * convention reads at observation-stop time. The lifecycle here:
 *
 * <pre>
 * 1. Observation starts (built from initial request)
 * 2. CyclesBudgetAdvisor.adviseCall runs:
 *      a. reserveOrFailOpen returns reservation_id
 *      b. advisor puts reservation_id into request.context()
 *      c. chain.nextCall runs the provider call
 *      d. advisor commits the reservation
 * 3. Observation stops
 * 4. Convention.getHighCardinalityKeyValues reads from request.context()
 *    → emits cycles.reservation_id KeyValue on the trace
 * </pre>
 *
 * <p>These are <em>internal</em> coordination keys, not public API. Users don't
 * write to them directly. Listed in a constants class so the advisor and convention
 * agree on the spelling without copy-pasting strings.
 */
public final class CyclesObservationContextKeys {

    /**
     * Context map key under which {@code CyclesBudgetAdvisor} /
     * {@code CyclesBudgetStreamAdvisor} stash the active reservation id for the
     * convention to read at observation-stop time. Value is a {@link String}.
     */
    public static final String RESERVATION_ID = "cycles.reservation_id";

    private CyclesObservationContextKeys() {
        // Constants class; not instantiable.
    }
}
