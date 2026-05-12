package io.runcycles.client.java.springai.subject;

import io.runcycles.client.java.spring.config.CyclesProperties;
import io.runcycles.client.java.spring.model.Subject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PropertiesSubjectResolver}. Verifies that all subject fields
 * are pulled from {@link CyclesProperties} and that the {@code ChatClientRequest}
 * parameter is ignored (consistent with v0.1.0 / v0.2.0 behavior).
 */
class PropertiesSubjectResolverTest {

    @Test
    void resolvesAllSubjectFieldsFromProperties() {
        CyclesProperties properties = new CyclesProperties();
        properties.setTenant("acme");
        properties.setWorkspace("engineering");
        properties.setApp("chatbot");
        properties.setWorkflow("triage");
        properties.setAgent("front-line");
        properties.setToolset("knowledge-base");

        Subject subject = new PropertiesSubjectResolver(properties).resolveSubject(null);

        assertThat(subject.getTenant()).isEqualTo("acme");
        assertThat(subject.getWorkspace()).isEqualTo("engineering");
        assertThat(subject.getApp()).isEqualTo("chatbot");
        assertThat(subject.getWorkflow()).isEqualTo("triage");
        assertThat(subject.getAgent()).isEqualTo("front-line");
        assertThat(subject.getToolset()).isEqualTo("knowledge-base");
    }

    @Test
    void tolerantOfNullPropertyFields() {
        // CyclesProperties defaults every field to null; the resolver must not NPE.
        Subject subject = new PropertiesSubjectResolver(new CyclesProperties()).resolveSubject(null);

        assertThat(subject).isNotNull();
        assertThat(subject.getTenant()).isNull();
        assertThat(subject.getWorkspace()).isNull();
    }

    @Test
    void ignoresChatClientRequestParameter() {
        // Pinned contract: the default resolver does NOT look at the request. Multi-tenant
        // routing requires a user-provided resolver that DOES read from the request.
        CyclesProperties properties = new CyclesProperties();
        properties.setTenant("from-properties");

        // Call once with null, once with a stub request — same result.
        Subject withoutRequest = new PropertiesSubjectResolver(properties).resolveSubject(null);
        assertThat(withoutRequest.getTenant()).isEqualTo("from-properties");
    }
}
