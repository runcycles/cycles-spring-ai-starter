package io.runcycles.client.java.springai;

import io.runcycles.client.java.spring.client.CyclesClient;
import io.runcycles.client.java.spring.config.CyclesProperties;
import io.runcycles.client.java.spring.model.CommitRequest;
import io.runcycles.client.java.spring.model.CyclesResponse;
import io.runcycles.client.java.spring.model.ReservationCreateRequest;
import io.runcycles.client.java.spring.model.Subject;
import io.runcycles.client.java.springai.advisor.CyclesBudgetAdvisor;
import io.runcycles.client.java.springai.autoconfigure.CyclesSpringAiProperties;
import io.runcycles.client.java.springai.subject.PropertiesSubjectResolver;
import io.runcycles.client.java.springai.subject.SubjectResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Integration-style tests for {@link CyclesBudgetAdvisor} with custom
 * {@link SubjectResolver}s — verifies that per-request routing works end-to-end:
 * the advisor invokes the user-provided resolver, the resolver sees the request,
 * and the resolved subject is what gets sent to Cycles in the reservation.
 */
@ExtendWith(MockitoExtension.class)
class CyclesBudgetAdvisorSubjectResolverTest {

    @Mock CyclesClient cyclesClient;
    @Mock CallAdvisorChain chain;
    @Mock ChatClientRequest request;
    @Mock ChatClientResponse response;

    @Test
    void backwardCompatConstructorUsesPropertyDerivedSubject() {
        // Old 3-arg constructor (no SubjectResolver) is preserved for backward
        // compatibility — it must use a property-derived resolver internally,
        // matching v0.1.0 / v0.2.0 behavior.
        CyclesProperties cyclesProperties = new CyclesProperties();
        cyclesProperties.setTenant("legacy-tenant");
        cyclesProperties.setApp("legacy-app");
        CyclesSpringAiProperties springAiProperties = new CyclesSpringAiProperties();

        CyclesBudgetAdvisor advisor = new CyclesBudgetAdvisor(cyclesClient, cyclesProperties, springAiProperties);

        ArgumentCaptor<ReservationCreateRequest> captor =
                ArgumentCaptor.forClass(ReservationCreateRequest.class);
        when(cyclesClient.createReservation(captor.capture()))
                .thenReturn(reservationAllow("res-legacy"));
        when(chain.nextCall(request)).thenReturn(response);
        when(cyclesClient.commitReservation(anyString(), any(CommitRequest.class)))
                .thenReturn(CyclesResponse.success(200, Map.of()));

        advisor.adviseCall(request, chain);

        Subject sent = captor.getValue().getSubject();
        assertThat(sent.getTenant()).isEqualTo("legacy-tenant");
        assertThat(sent.getApp()).isEqualTo("legacy-app");
    }

    @Test
    void userResolverCanRouteSubjectPerRequest() {
        // Multi-tenant scenario: a request-aware resolver extracts the tenant from
        // a thread-local / request context / auth principal. Verify the advisor
        // calls the resolver and uses its result.
        CyclesProperties cyclesProperties = new CyclesProperties();
        cyclesProperties.setApp("fixed-app");
        CyclesSpringAiProperties springAiProperties = new CyclesSpringAiProperties();

        // A "per-request" resolver that bumps the tenant counter every call so we can
        // distinguish whether the advisor truly calls it each time.
        AtomicReference<Integer> counter = new AtomicReference<>(0);
        SubjectResolver dynamicResolver = req -> {
            int n = counter.updateAndGet(c -> c + 1);
            return Subject.builder()
                    .tenant("tenant-" + n)
                    .app(cyclesProperties.getApp())
                    .build();
        };

        CyclesBudgetAdvisor advisor = new CyclesBudgetAdvisor(
                cyclesClient, cyclesProperties, springAiProperties, dynamicResolver);

        ArgumentCaptor<ReservationCreateRequest> captor =
                ArgumentCaptor.forClass(ReservationCreateRequest.class);
        when(cyclesClient.createReservation(captor.capture()))
                .thenReturn(reservationAllow("res-1"))
                .thenReturn(reservationAllow("res-2"));
        when(chain.nextCall(request)).thenReturn(response);
        when(cyclesClient.commitReservation(anyString(), any(CommitRequest.class)))
                .thenReturn(CyclesResponse.success(200, Map.of()));

        advisor.adviseCall(request, chain);
        advisor.adviseCall(request, chain);

        // Two calls => two distinct resolver invocations => distinct tenants on the
        // two reservations.
        assertThat(captor.getAllValues()).hasSize(2);
        assertThat(captor.getAllValues().get(0).getSubject().getTenant()).isEqualTo("tenant-1");
        assertThat(captor.getAllValues().get(1).getSubject().getTenant()).isEqualTo("tenant-2");
        // App field still comes from the resolver (which read CyclesProperties).
        assertThat(captor.getAllValues().get(0).getSubject().getApp()).isEqualTo("fixed-app");
    }

    @Test
    void resolverReceivesTheChatClientRequest() {
        // The resolver signature takes a ChatClientRequest; verify the advisor passes
        // the actual request instance (not null) so users can extract context from it.
        CyclesProperties cyclesProperties = new CyclesProperties();
        CyclesSpringAiProperties springAiProperties = new CyclesSpringAiProperties();

        AtomicReference<ChatClientRequest> capturedRequest = new AtomicReference<>();
        SubjectResolver captureResolver = req -> {
            capturedRequest.set(req);
            return Subject.builder().tenant("captured").build();
        };

        CyclesBudgetAdvisor advisor = new CyclesBudgetAdvisor(
                cyclesClient, cyclesProperties, springAiProperties, captureResolver);

        when(cyclesClient.createReservation(any(ReservationCreateRequest.class)))
                .thenReturn(reservationAllow("res"));
        when(chain.nextCall(request)).thenReturn(response);
        when(cyclesClient.commitReservation(anyString(), any(CommitRequest.class)))
                .thenReturn(CyclesResponse.success(200, Map.of()));

        advisor.adviseCall(request, chain);

        assertThat(capturedRequest.get()).isSameAs(request);
    }

    @Test
    void explicitDefaultResolverProducesSameSubjectAsLegacyConstructor() {
        // Sanity check that PropertiesSubjectResolver (the default impl wired by the
        // auto-config) is observably equivalent to the legacy property-reading path.
        CyclesProperties cyclesProperties = new CyclesProperties();
        cyclesProperties.setTenant("eq-tenant");
        cyclesProperties.setWorkspace("eq-ws");
        CyclesSpringAiProperties springAiProperties = new CyclesSpringAiProperties();

        CyclesBudgetAdvisor advisor = new CyclesBudgetAdvisor(
                cyclesClient, cyclesProperties, springAiProperties,
                new PropertiesSubjectResolver(cyclesProperties));

        ArgumentCaptor<ReservationCreateRequest> captor =
                ArgumentCaptor.forClass(ReservationCreateRequest.class);
        when(cyclesClient.createReservation(captor.capture()))
                .thenReturn(reservationAllow("res"));
        when(chain.nextCall(request)).thenReturn(response);
        when(cyclesClient.commitReservation(anyString(), any(CommitRequest.class)))
                .thenReturn(CyclesResponse.success(200, Map.of()));

        advisor.adviseCall(request, chain);

        Subject sent = captor.getValue().getSubject();
        assertThat(sent.getTenant()).isEqualTo("eq-tenant");
        assertThat(sent.getWorkspace()).isEqualTo("eq-ws");
    }

    private static CyclesResponse<Map<String, Object>> reservationAllow(String id) {
        return CyclesResponse.success(200, Map.of(
                "decision", "ALLOW",
                "reservation_id", id,
                "scope_path", "tenant/x"
        ));
    }
}
