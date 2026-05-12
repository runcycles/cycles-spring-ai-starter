package io.runcycles.client.java.springai.tool;

import io.runcycles.client.java.spring.client.CyclesClient;
import io.runcycles.client.java.spring.config.CyclesProperties;
import io.runcycles.client.java.springai.autoconfigure.CyclesSpringAiProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.tool.ToolCallback;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CyclesToolGate}. Verifies the factory returns a wrapped
 * {@link CyclesToolCallback} around the supplied delegate.
 */
@ExtendWith(MockitoExtension.class)
class CyclesToolGateTest {

    @Mock CyclesClient cyclesClient;
    @Mock ToolCallback delegate;

    @Test
    void wrapReturnsCyclesToolCallbackAroundDelegate() {
        CyclesProperties cyclesProperties = new CyclesProperties();
        CyclesSpringAiProperties springAiProperties = new CyclesSpringAiProperties();
        CyclesToolGate gate = new CyclesToolGate(cyclesClient, cyclesProperties, springAiProperties);

        CyclesToolCallback wrapped = gate.wrap(delegate);

        assertThat(wrapped).isNotNull();
        assertThat(wrapped).isInstanceOf(CyclesToolCallback.class);
    }
}
