# Test Coverage Gaps

This file tracks known coverage shortfalls against the project's **95%-or-higher coverage rule** (see [CLAUDE.md](./CLAUDE.md)). Entries are removed as gaps are closed.

## Current state (0.3.0)

- Library bundle (`cycles-spring-ai-starter`): bundle coverage gate (`BUNDLE INSTRUCTION ≥ 95%`) met. Latest `mvn -B clean verify`: **24 missed / 1108 covered instructions (98%)**, **6 missed / 102 covered branches (94%)** across 12 classes.
- Demo module (`cycles-spring-ai-demo`): not subject to the rule (not published).
- Test count: **142 tests across 12 test classes** — call advisor + 2 (subject resolver / token estimator integration), stream advisor, tool callback, tool gate, auto-config, observation convention, properties subject resolver, chars-per-token estimator, jtokkit estimator, end-to-end integration.

Covered surfaces:

- `CyclesSpringAiProperties` — all getters/setters across 13 properties, plus property-validation paths for every numeric setter that rejects negatives. **100% / 100%.**
- `CyclesBudgetDeniedException`, `PropertiesSubjectResolver`, `CharsPerTokenEstimator`, `JtokkitPromptTokenEstimator`, `CyclesToolGate`, `CyclesToolCallback` — **100% / 100%** on each.
- `CyclesBudgetLifecycle` — 100% instruction, 98% branch (1 missed branch on a defensive null-guard).
- `CyclesBudgetAdvisor` — 100% instruction, 88% branch (1 missed branch on theme C's `request != null` guard; reservationId is non-null but request happens to be null only in tests, never in production).
- `CyclesBudgetStreamAdvisor` — 91% instruction, 92% branch. 12 missed instructions, 1 missed branch, 1 missed method — the missed method is the 4-arg backward-compat constructor (auto-config goes through the 5-arg version with `PromptTokenEstimator`, so the 4-arg path is dead code under the auto-config path; tests of the new code path took the 5-arg path).
- `CyclesSpringAiAutoConfiguration` — 91% instruction, 83% branch. 8 missed instructions, 1 missed branch on the jtokkit-absent fallback in `cyclesPromptTokenEstimator` (fires when `cycles.spring-ai.token-estimator-encoding` is set but jtokkit isn't on the classpath; would require classloader manipulation to test).
- `CyclesChatClientObservationConvention` — 96% instruction, 86% branch. 4 missed instructions, 2 missed branches on defensive null-guards in `extractReservationId` (`context==null`, `request==null`, `ctx==null`) — unreachable under Spring AI's contract that the observation context, request, and context map are all non-null.

## Open gaps (acceptable)

Three coverage gaps remain, all documented in the code and accepted as such. Each would require disproportionate test infrastructure to close. Bundle coverage gate stays well above the 95% threshold even with them open.

1. **Backward-compat constructors on `CyclesBudgetAdvisor` and `CyclesBudgetStreamAdvisor`** (~12 instructions, 1 method). The 3-arg / 4-arg legacy constructors were preserved across themes B and A1 so existing callers (and any direct-instantiation tests outside this codebase) don't break. The 5-arg constructor is what auto-config uses; the legacy variants are dead-code under the auto-config path. Could be removed in a major version bump, or covered with one direct-instantiation test apiece if churn becomes acceptable.
2. **`CyclesSpringAiAutoConfiguration.cyclesPromptTokenEstimator` jtokkit-absent fallback** (~8 instructions, 1 branch). Fires when the `cycles.spring-ai.token-estimator-encoding` property is set but `com.knuddels.jtokkit.Encodings` isn't on the classpath. Closing this would require a custom `ClassLoader` that hides the optional jtokkit jar. The path is exercised in production deploys where someone enables the property without adding the optional dep; the WARN log surfaces the misconfig at startup.
3. **`CyclesChatClientObservationConvention` defensive null-guards** (~4 instructions, 2 branches). `extractReservationId` returns null if `context`, `getRequest()`, or `request.context()` ever returns null. Spring AI's real contract is that `ChatClientObservationContext` always has a non-null request with a non-null mutable context map, so these branches are unreachable in production. Kept for defensive shape.

Absolute counts (vs ~1100 instructions and ~100 branches in the bundle): 24 missed instructions, 6 missed branches. Bundle gate at INSTRUCTION ≥ 95% requires at most 56 missed instructions — current state is well within budget.

## Test strategy notes

- All unit tests use Mockito for `CyclesClient`. There is no live Cycles server in the unit-test suite — that's `cycles-server` integration territory.
- Stream tests use Reactor's `StepVerifier` to drive the Flux lifecycle (complete / error / cancel) without a real provider.
- The auto-configuration tests stub `CyclesClient` to satisfy `@ConditionalOnBean(CyclesClient.class)`.
- The integration test (0.3.0+) uses a stub `ChatModel` (anonymous `ChatModel` impl returning canned `ChatResponse`) to exercise the auto-attached advisor end-to-end without a real LLM provider.
- A Testcontainers-ollama smoke test is appropriate for a nightly CI job once a real-provider lane is needed, but is overkill for PR CI.
