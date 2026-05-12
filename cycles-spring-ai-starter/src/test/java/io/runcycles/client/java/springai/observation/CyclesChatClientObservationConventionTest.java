package io.runcycles.client.java.springai.observation;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import io.runcycles.client.java.spring.config.CyclesProperties;
import io.runcycles.client.java.springai.autoconfigure.CyclesSpringAiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.observation.ChatClientObservationContext;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.observation.AiOperationMetadata;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CyclesChatClientObservationConvention}. Verifies that the
 * Cycles attribution tags are appended to the low-cardinality KeyValues, including
 * null-safe handling when SDK properties are unset.
 */
@ExtendWith(MockitoExtension.class)
class CyclesChatClientObservationConventionTest {

    private CyclesProperties cyclesProperties;
    private CyclesSpringAiProperties springAiProperties;
    private ChatClientObservationContext context;

    @BeforeEach
    void setUp() {
        cyclesProperties = new CyclesProperties();
        springAiProperties = new CyclesSpringAiProperties();
        // lenient() because not every test exercises super.getLowCardinalityKeyValues
        // — the high-cardinality tests don't read getOperationMetadata, so strict
        // Mockito would otherwise flag the stub as unused on those.
        context = mock(ChatClientObservationContext.class);
        lenient().when(context.getOperationMetadata())
                .thenReturn(new AiOperationMetadata("framework", "spring_ai"));
    }

    @Test
    void emitsAllFiveCyclesKeyValues() {
        cyclesProperties.setTenant("acme");
        cyclesProperties.setWorkspace("engineering");
        cyclesProperties.setApp("chatbot");
        springAiProperties.setActionKind("llm.chat");
        springAiProperties.setActionName("gpt-4o");

        CyclesChatClientObservationConvention convention =
                new CyclesChatClientObservationConvention(cyclesProperties, springAiProperties);

        KeyValues keyValues = convention.getLowCardinalityKeyValues(context);

        assertThat(keyValues).contains(
                KeyValue.of("cycles.tenant",      "acme"),
                KeyValue.of("cycles.workspace",   "engineering"),
                KeyValue.of("cycles.app",         "chatbot"),
                KeyValue.of("cycles.action_kind", "llm.chat"),
                KeyValue.of("cycles.action_name", "gpt-4o")
        );
    }

    @Test
    void inheritsDefaultConventionKeysFromSuper() {
        // Spring AI's DefaultChatClientObservationConvention emits at least one
        // low-cardinality key (e.g. spring.ai.kind). Verify we don't shadow that by
        // checking the result contains MORE than just our 5 Cycles keys — i.e. super
        // was actually called and its keys were merged in.
        CyclesChatClientObservationConvention convention =
                new CyclesChatClientObservationConvention(cyclesProperties, springAiProperties);

        KeyValues keyValues = convention.getLowCardinalityKeyValues(context);

        long count = keyValues.stream().count();
        assertThat(count).isGreaterThan(5L);
    }

    // ---- High-cardinality: reservation_id correlation ----------------------------

    @Test
    void emitsReservationIdAsHighCardinalityWhenPresent() {
        // Advisor wrote the reservation_id into request.context() after a successful
        // reserve. The convention should pick it up and emit it as a high-cardinality
        // KeyValue for trace ↔ reservation correlation.
        Map<String, Object> ctx = new HashMap<>();
        ctx.put(CyclesObservationContextKeys.RESERVATION_ID, "res-abc-123");
        ChatClientRequest request = stubRequest(ctx);
        lenient().when(context.getRequest()).thenReturn(request);

        CyclesChatClientObservationConvention convention =
                new CyclesChatClientObservationConvention(cyclesProperties, springAiProperties);

        KeyValues highCard = convention.getHighCardinalityKeyValues(context);

        assertThat(highCard).contains(KeyValue.of("cycles.reservation_id", "res-abc-123"));
    }

    @Test
    void omitsReservationIdWhenAbsentFromContext() {
        // Fail-open reserve skip path: no reservation was created, so nothing was put
        // in the context. Convention should not emit a tag with null / empty value.
        Map<String, Object> ctx = new HashMap<>();  // empty
        ChatClientRequest request = stubRequest(ctx);
        lenient().when(context.getRequest()).thenReturn(request);

        CyclesChatClientObservationConvention convention =
                new CyclesChatClientObservationConvention(cyclesProperties, springAiProperties);

        KeyValues highCard = convention.getHighCardinalityKeyValues(context);

        assertThat(highCard).noneMatch(kv -> kv.getKey().equals("cycles.reservation_id"));
    }

    @Test
    void omitsReservationIdWhenPropertyDisabled() {
        // Operator opted out of emitting the high-cardinality reservation_id tag
        // (e.g. tracing-backend cost concerns).
        springAiProperties.setEmitReservationIdOnTrace(false);
        Map<String, Object> ctx = new HashMap<>();
        ctx.put(CyclesObservationContextKeys.RESERVATION_ID, "res-disabled");
        ChatClientRequest request = stubRequest(ctx);
        lenient().when(context.getRequest()).thenReturn(request);

        CyclesChatClientObservationConvention convention =
                new CyclesChatClientObservationConvention(cyclesProperties, springAiProperties);

        KeyValues highCard = convention.getHighCardinalityKeyValues(context);

        assertThat(highCard).noneMatch(kv -> kv.getKey().equals("cycles.reservation_id"));
    }

    @Test
    void onlyEmitsReservationIdAsHighCardinalityKey() {
        // We deliberately don't inherit super.getHighCardinalityKeyValues (NPEs on
        // insufficiently-stubbed requests, and Spring AI's defaults don't include
        // anything we currently care about). Confirm the only high-card key we emit
        // is cycles.reservation_id.
        Map<String, Object> ctx = new HashMap<>();
        ctx.put(CyclesObservationContextKeys.RESERVATION_ID, "res-merge");
        ChatClientRequest request = stubRequest(ctx);
        lenient().when(context.getRequest()).thenReturn(request);

        CyclesChatClientObservationConvention convention =
                new CyclesChatClientObservationConvention(cyclesProperties, springAiProperties);

        KeyValues highCard = convention.getHighCardinalityKeyValues(context);
        assertThat(highCard).hasSize(1);
        assertThat(highCard).contains(KeyValue.of("cycles.reservation_id", "res-merge"));
    }

    @Test
    void omitsReservationIdWhenContextItselfIsNull() {
        // Defensive guard: extractReservationId returns null when the
        // ChatClientObservationContext parameter is null. Spring AI's real contract
        // never passes null, but the guard is there as belt-and-suspenders.
        CyclesChatClientObservationConvention convention =
                new CyclesChatClientObservationConvention(cyclesProperties, springAiProperties);

        KeyValues highCard = convention.getHighCardinalityKeyValues(null);

        // No reservation_id emitted; no NPE.
        assertThat(highCard).noneMatch(kv -> kv.getKey().equals("cycles.reservation_id"));
    }

    @Test
    void omitsReservationIdWhenRequestContextMapIsNull() {
        // Defensive guard: request.context() returns null. Spring AI's contract is that
        // the context map is always non-null + mutable, but the guard is there in case
        // a third-party advisor wraps the request and breaks the contract.
        ChatClientRequest request = mock(ChatClientRequest.class);
        lenient().when(request.prompt()).thenReturn(new Prompt(List.of(new UserMessage("test"))));
        lenient().when(request.context()).thenReturn(null);
        lenient().when(context.getRequest()).thenReturn(request);

        CyclesChatClientObservationConvention convention =
                new CyclesChatClientObservationConvention(cyclesProperties, springAiProperties);

        KeyValues highCard = convention.getHighCardinalityKeyValues(context);

        assertThat(highCard).noneMatch(kv -> kv.getKey().equals("cycles.reservation_id"));
    }

    @Test
    void omitsReservationIdWhenRequestIsNull() {
        // ChatClientObservationContext.getRequest() returns null in some scenarios
        // (early observation lifecycle, broken integration). Convention must defend.
        lenient().when(context.getRequest()).thenReturn(null);

        CyclesChatClientObservationConvention convention =
                new CyclesChatClientObservationConvention(cyclesProperties, springAiProperties);

        KeyValues highCard = convention.getHighCardinalityKeyValues(context);

        assertThat(highCard).noneMatch(kv -> kv.getKey().equals("cycles.reservation_id"));
    }

    /**
     * Builds a {@link ChatClientRequest} stub with a non-null prompt + context map
     * — enough to satisfy super.getHighCardinalityKeyValues which reads
     * {@code request.prompt().getOptions()}.
     */
    private static ChatClientRequest stubRequest(Map<String, Object> contextMap) {
        ChatClientRequest req = mock(ChatClientRequest.class);
        lenient().when(req.prompt()).thenReturn(new Prompt(List.of(new UserMessage("test"))));
        lenient().when(req.context()).thenReturn(contextMap);
        return req;
    }

    // ---- Low-cardinality tests (existing) ----------------------------------------

    @Test
    void substitutesUnknownForNullProperties() {
        // tenant/workspace/app default to null on CyclesProperties when unset.
        // Micrometer KeyValues.of() rejects null values — our convention must defend.
        assertThat(cyclesProperties.getTenant()).isNull();
        assertThat(cyclesProperties.getWorkspace()).isNull();
        assertThat(cyclesProperties.getApp()).isNull();

        CyclesChatClientObservationConvention convention =
                new CyclesChatClientObservationConvention(cyclesProperties, springAiProperties);

        KeyValues keyValues = convention.getLowCardinalityKeyValues(context);

        assertThat(keyValues).contains(
                KeyValue.of("cycles.tenant",    "unknown"),
                KeyValue.of("cycles.workspace", "unknown"),
                KeyValue.of("cycles.app",       "unknown")
        );
        // Action kind/name have non-null defaults from CyclesSpringAiProperties, so they
        // carry through verbatim — confirming we only substitute on real nulls.
        assertThat(keyValues).contains(
                KeyValue.of("cycles.action_kind", "llm.chat"),
                KeyValue.of("cycles.action_name", "spring-ai-chat")
        );
    }
}
