package io.runcycles.client.java.springai.advisor;

import io.runcycles.client.java.springai.autoconfigure.CyclesSpringAiProperties;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.core.Ordered;

/**
 * Pre-call budget gate and post-call usage recorder for Spring AI {@link org.springframework.ai.chat.client.ChatClient}
 * invocations.
 *
 * <p><strong>Scaffold:</strong> currently a pass-through. The real implementation will:
 * <ol>
 *   <li>Pre-call: ask the Cycles server whether the configured budget can accommodate this call,
 *       deny (throw {@code CyclesBudgetExceededException}) when over.</li>
 *   <li>Post-call: read {@code ChatResponse.Usage} and record actual token / cost usage back to
 *       Cycles to draw down the reservation.</li>
 *   <li>Error path: when {@link CyclesSpringAiProperties#isFailOpen()} is true, log and proceed
 *       on Cycles-server errors; when false, surface the error.</li>
 * </ol>
 *
 * <p>Order: runs before any cost-incurring advisor so denial happens before the LLM call.
 */
public class CyclesBudgetAdvisor implements CallAdvisor {

    private final CyclesSpringAiProperties properties;

    public CyclesBudgetAdvisor(CyclesSpringAiProperties properties) {
        this.properties = properties;
    }

    @Override
    public String getName() {
        return "cycles-budget";
    }

    @Override
    public int getOrder() {
        // Run early so budget denial happens before any cost-incurring downstream advisor.
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        // TODO(v0.1.0): pre-call Cycles budget check.
        ChatClientResponse response = chain.nextCall(request);
        // TODO(v0.1.0): post-call Cycles usage record from response.chatResponse().getMetadata().getUsage().
        return response;
    }
}
