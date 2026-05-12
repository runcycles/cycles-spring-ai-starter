package io.runcycles.client.java.springai.observation;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import io.runcycles.client.java.spring.config.CyclesProperties;
import io.runcycles.client.java.springai.autoconfigure.CyclesSpringAiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.observation.ChatClientObservationContext;
import org.springframework.ai.observation.AiOperationMetadata;

import static org.assertj.core.api.Assertions.assertThat;
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
        // Stub the bits of the context that super.getLowCardinalityKeyValues reads
        // (operation type + provider via AiOperationMetadata). Our convention's own
        // logic doesn't touch the context — these stubs only keep super from NPE'ing.
        context = mock(ChatClientObservationContext.class);
        when(context.getOperationMetadata())
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
