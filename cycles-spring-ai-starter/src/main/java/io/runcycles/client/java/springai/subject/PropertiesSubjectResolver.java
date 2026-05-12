package io.runcycles.client.java.springai.subject;

import io.runcycles.client.java.spring.config.CyclesProperties;
import io.runcycles.client.java.spring.model.Subject;
import org.springframework.ai.chat.client.ChatClientRequest;

/**
 * Default {@link SubjectResolver} implementation. Reads
 * tenant/workspace/app/workflow/agent/toolset from {@link CyclesProperties} on every
 * call. The {@link ChatClientRequest} parameter is ignored — every call from this
 * application gets the same subject.
 *
 * <p>This preserves the v0.1.0 / v0.2.0 behavior. Users who need per-request subject
 * routing (e.g. tenant from an authenticated principal) supply their own
 * {@link SubjectResolver} bean; the auto-configuration registers this implementation
 * only when no user-provided bean is present.
 */
public class PropertiesSubjectResolver implements SubjectResolver {

    private final CyclesProperties cyclesProperties;

    /**
     * Constructs a resolver that reads subject defaults from the supplied properties.
     *
     * @param cyclesProperties the SDK-level configuration carrying the subject defaults.
     */
    public PropertiesSubjectResolver(CyclesProperties cyclesProperties) {
        this.cyclesProperties = cyclesProperties;
    }

    @Override
    public Subject resolveSubject(ChatClientRequest request) {
        return Subject.builder()
                .tenant(cyclesProperties.getTenant())
                .workspace(cyclesProperties.getWorkspace())
                .app(cyclesProperties.getApp())
                .workflow(cyclesProperties.getWorkflow())
                .agent(cyclesProperties.getAgent())
                .toolset(cyclesProperties.getToolset())
                .build();
    }
}
