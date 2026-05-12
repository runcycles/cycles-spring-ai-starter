# Test Coverage Gaps

This file tracks known coverage shortfalls against the project's **95%-or-higher coverage rule** (see [CLAUDE.md](./CLAUDE.md)). Entries are removed as gaps are closed.

## Current state (0.3.0-SNAPSHOT)

- Library bundle (`cycles-spring-ai-starter`): bundle coverage gate (`BUNDLE INSTRUCTION ≥ 95%`) met. Per-class coverage is at or near 100% for every class except `CyclesSpringAiAutoConfiguration` (8 missed instructions, ~91% on that class alone — the missed paths are the jtokkit-absent fallback in `cyclesPromptTokenEstimator`, which requires classloader manipulation to test) and `CyclesChatClientObservationConvention` (~96%, the missed paths are defensive null-guards on the observation context that are unreachable under Spring AI's real contract).
- Demo module (`cycles-spring-ai-demo`): not subject to the rule (not published).
- Test count: **142 tests across 11 test classes** — call advisor, stream advisor, tool callback, tool gate, auto-config, observation convention, properties subject resolver, chars-per-token estimator, jtokkit estimator, advisor-subject-resolver integration, advisor-token-estimator integration, and end-to-end integration.

Covered surfaces:

- `CyclesSpringAiProperties` — all getters/setters across 13 properties, plus property-validation paths for every numeric setter that rejects negatives.
- `CyclesSpringAiAutoConfiguration` — full wiring matrix including subject-resolver wiring (default + user override), token-estimator wiring (chars/4 default, jtokkit when property set + classpath present, invalid encoding fails bean init, empty property treated as unset).
- `CyclesBudgetAdvisor` — full reserve → call → commit lifecycle, all fail-open / fail-closed variants, real `ChatResponse.Usage` extraction modes, prompt-based estimate paths, user-provided `SubjectResolver` routing, user-provided `PromptTokenEstimator`, reservation_id stashed in request context for trace correlation.
- `CyclesBudgetStreamAdvisor` — `Flux.defer`-wrapped lifecycle, per-subscription reservation, reactive-idiomatic onError surfacing, prompt-based estimate parity with non-streaming.
- `CyclesToolCallback` — delegation + reserve/commit/release lifecycle + tool-specific action labels + fail-open variants.
- `CyclesToolGate` — factory wires `SubjectResolver` through to the produced `CyclesToolCallback`.
- `CyclesChatClientObservationConvention` — low-cardinality Cycles tags + `cycles.reservation_id` high-cardinality emission when present; operator opt-out via property; defensive null-guards on the observation context.
- `PropertiesSubjectResolver` — all subject fields resolved, null property fields tolerated, request parameter ignored (pinned contract).
- `CharsPerTokenEstimator` — chars/4 default, explicit ratio constructor, null/empty handling, multimodal null-text messages skipped.
- `JtokkitPromptTokenEstimator` — real BPE encoding (pinned token counts to guard against jtokkit-version regressions), encoding name case-insensitive, EncodingType enum constructor, unknown encoding rejected, null/empty handling.
- `CyclesSpringAiIntegrationTest` (NEW in 0.3.0) — end-to-end Spring context boot with the real auto-config: advisor attachment + reserve/call/commit lifecycle through a stub `ChatModel`; user-provided `SubjectResolver` routing through the full auto-config chain; observation convention available for opt-in; reservation_id context-key alignment.
- `CyclesBudgetDeniedException` — `reasonCode` / `scopePath` accessors covered transitively via deny paths.

## Open gaps

Two acceptable gaps remain. Both are documented in the code and would require disproportionate test infrastructure to close:

1. **`CyclesSpringAiAutoConfiguration.cyclesPromptTokenEstimator` jtokkit-absent fallback** — fires when the `cycles.spring-ai.token-estimator-encoding` property is set but `com.knuddels.jtokkit.Encodings` isn't on the classpath. Closing this would require a custom `ClassLoader` that hides the optional jtokkit jar. The path is exercised in production deploys where someone enables the property without adding the optional dep; the WARN log surfaces the misconfig at startup.
2. **`CyclesChatClientObservationConvention` defensive null-guards** — `extractReservationId` returns null if `context`, `getRequest()`, or `request.context()` ever returns null. Spring AI's real contract is that `ChatClientObservationContext` always has a non-null request with a non-null mutable context map, so these branches are unreachable in production. Kept for defensive shape.

Bundle coverage gate remains well above the 95% threshold; the absolute counts are 8 missed instructions / 3 missed branches on a bundle of ~1100 instructions and ~100 branches.

## Test strategy notes

- All unit tests use Mockito for `CyclesClient`. There is no live Cycles server in the unit-test suite — that's `cycles-server` integration territory.
- Stream tests use Reactor's `StepVerifier` to drive the Flux lifecycle (complete / error / cancel) without a real provider.
- The auto-configuration tests stub `CyclesClient` to satisfy `@ConditionalOnBean(CyclesClient.class)`.
- The integration test (0.3.0+) uses a stub `ChatModel` (anonymous `ChatModel` impl returning canned `ChatResponse`) to exercise the auto-attached advisor end-to-end without a real LLM provider.
- A Testcontainers-ollama smoke test is appropriate for a nightly CI job once a real-provider lane is needed, but is overkill for PR CI.
