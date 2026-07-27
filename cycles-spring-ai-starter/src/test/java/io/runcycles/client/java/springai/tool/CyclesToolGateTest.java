package io.runcycles.client.java.springai.tool;

import io.runcycles.client.java.spring.client.CyclesClient;
import io.runcycles.client.java.spring.config.CyclesProperties;
import io.runcycles.client.java.spring.retry.CommitRetryEngine;
import io.runcycles.client.java.spring.retry.JournaledCommitRetryEngine;
import io.runcycles.client.java.springai.advisor.CyclesBudgetLifecycle;
import io.runcycles.client.java.springai.autoconfigure.CyclesSpringAiProperties;
import io.runcycles.client.java.springai.subject.PropertiesSubjectResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CyclesToolGate}. Verifies the factory returns a wrapped
 * {@link CyclesToolCallback} around the supplied delegate, and that every wrap
 * shares the gate's single {@link CommitRetryEngine}.
 *
 * <p>Deprecated-constructor tests build a REAL {@link JournaledCommitRetryEngine};
 * the {@code CyclesProperties} used carry no base-url/api-key, so the engine's
 * on-disk journal is disabled and nothing touches the real user home.
 */
@ExtendWith(MockitoExtension.class)
class CyclesToolGateTest {

    @Mock CyclesClient cyclesClient;
    @Mock ToolCallback delegate;
    @Mock ToolCallback secondDelegate;
    @Mock CommitRetryEngine retryEngine;

    /** Digs the retry engine out of a wrapped callback's private lifecycle. */
    private static CommitRetryEngine engineOf(CyclesToolCallback callback) {
        CyclesBudgetLifecycle lifecycle =
                (CyclesBudgetLifecycle) ReflectionTestUtils.getField(callback, "lifecycle");
        return (CommitRetryEngine) ReflectionTestUtils.getField(lifecycle, "retryEngine");
    }

    @Test
    @SuppressWarnings("deprecation")
    void wrapReturnsCyclesToolCallbackAroundDelegate() {
        CyclesProperties cyclesProperties = new CyclesProperties();
        CyclesSpringAiProperties springAiProperties = new CyclesSpringAiProperties();
        CyclesToolGate gate = new CyclesToolGate(cyclesClient, cyclesProperties, springAiProperties);

        CyclesToolCallback wrapped = gate.wrap(delegate);

        assertThat(wrapped).isNotNull();
        assertThat(wrapped).isInstanceOf(CyclesToolCallback.class);
    }

    @Test
    void engineInjectingGateWrapsWithSharedRetryEngine() {
        // Preferred wiring (matches the auto-configuration): the gate carries the
        // Spring-managed CommitRetryEngine and hands it to every wrapped tool so
        // failed tool commits share one durable engine.
        CyclesProperties cyclesProperties = new CyclesProperties();
        CyclesSpringAiProperties springAiProperties = new CyclesSpringAiProperties();
        CyclesToolGate gate = new CyclesToolGate(cyclesClient, cyclesProperties,
                springAiProperties, new PropertiesSubjectResolver(cyclesProperties), retryEngine);

        CyclesToolCallback wrapped = gate.wrap(delegate);

        assertThat(wrapped).isNotNull();
        assertThat(wrapped).isInstanceOf(CyclesToolCallback.class);
        assertThat(engineOf(wrapped)).isSameAs(retryEngine);
    }

    @Test
    @SuppressWarnings("deprecation")
    void deprecatedGateBuildsExactlyOneEngineSharedAcrossWraps() {
        // F2 hardening: the deprecated (engine-less) constructors build ONE journaled
        // engine per GATE instance — not one per wrap() call. A per-wrap engine would
        // multiply journal replays and retry threads by the number of wrapped tools.
        CyclesProperties cyclesProperties = new CyclesProperties();
        CyclesSpringAiProperties springAiProperties = new CyclesSpringAiProperties();
        CyclesToolGate gate = new CyclesToolGate(cyclesClient, cyclesProperties,
                springAiProperties, new PropertiesSubjectResolver(cyclesProperties));

        CyclesToolCallback first = gate.wrap(delegate);
        CyclesToolCallback second = gate.wrap(secondDelegate);

        CommitRetryEngine firstEngine = engineOf(first);
        assertThat(firstEngine).isInstanceOf(JournaledCommitRetryEngine.class);
        assertThat(engineOf(second)).isSameAs(firstEngine);
    }
}
