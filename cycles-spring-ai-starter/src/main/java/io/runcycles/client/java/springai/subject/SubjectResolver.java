package io.runcycles.client.java.springai.subject;

import io.runcycles.client.java.spring.model.Subject;
import org.springframework.ai.chat.client.ChatClientRequest;

/**
 * Resolves the Cycles {@link Subject} attribution for an in-flight reservation.
 *
 * <p>The default auto-configured implementation ({@code PropertiesSubjectResolver}) reads
 * tenant/workspace/app/workflow/agent/toolset from {@code CyclesProperties}, so every
 * call from a given app gets the same subject. Multi-tenant agents typically need
 * per-request attribution — extract the tenant from an authenticated principal, a request
 * header, or a thread-local. To do that, register your own {@code SubjectResolver} bean;
 * the default backs off via {@code @ConditionalOnMissingBean}.
 *
 * <p>Example: tenant from Spring Security principal.
 *
 * <pre>{@code
 * @Bean
 * public SubjectResolver tenantAwareSubjectResolver(CyclesProperties defaults) {
 *     return request -> {
 *         var auth = SecurityContextHolder.getContext().getAuthentication();
 *         String tenant = (auth != null && auth.isAuthenticated()) ? auth.getName() : defaults.getTenant();
 *         return Subject.builder()
 *                 .tenant(tenant)
 *                 .workspace(defaults.getWorkspace())
 *                 .app(defaults.getApp())
 *                 .build();
 *     };
 * }
 * }</pre>
 *
 * <p>The {@code request} parameter is the originating {@link ChatClientRequest} and may
 * be {@code null} when the resolver is invoked from a non-chat code path (tool gating —
 * tool callbacks don't carry a ChatClientRequest). Implementations should handle the
 * null case defensively, typically by falling back to the property-derived subject.
 */
@FunctionalInterface
public interface SubjectResolver {

    /**
     * Resolves the subject attribution for the given chat request.
     *
     * @param request the originating chat request, or {@code null} when invoked from a
     *                tool-gating path (no chat request is available).
     * @return the resolved subject; must not be {@code null}.
     */
    Subject resolveSubject(ChatClientRequest request);
}
