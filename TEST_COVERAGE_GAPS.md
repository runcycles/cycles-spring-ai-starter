# Test Coverage Gaps

This file tracks known coverage shortfalls against the project's **95%-or-higher coverage rule** (see [CLAUDE.md](./CLAUDE.md)). Entries are removed as gaps are closed.

## Current state (0.3.1-dev)

- Library bundle (`cycles-spring-ai-starter`): **100% instruction coverage, 100% branch coverage**. Latest `mvn -B clean verify`: 0 missed / 1444 covered instructions, 0 missed / 136 covered branches across 12 classes.
- Demo module (`cycles-spring-ai-demo`): not subject to the rule (not published).
- Test count: ~150 tests across 11 test files (5 source-package tests + 6 in subpackages).

Every class is at 100% / 100%:

- `CyclesSpringAiProperties`, `CyclesBudgetDeniedException`, `PropertiesSubjectResolver`, `CharsPerTokenEstimator`, `JtokkitPromptTokenEstimator`, `CyclesToolGate`, `CyclesToolCallback`, `CyclesBudgetLifecycle`, `CyclesBudgetAdvisor`, `CyclesBudgetStreamAdvisor`, `CyclesChatClientObservationConvention`, `CyclesSpringAiAutoConfiguration` — all 100% on both instruction and branch counters.

## How the previously-documented gaps were closed (post-0.3.0)

1. **Backward-compat constructors** — added direct-instantiation tests for `CyclesBudgetAdvisor(client, props, springProps)` and `CyclesBudgetStreamAdvisor(client, props, springProps, resolver)`. The 5-arg variants were already exercised via the auto-config / integration test path.
2. **Advisor request-null short-circuit** (theme C) — added tests on both the call and stream advisors that pass `request=null` while reservation succeeds; the `request != null` guard skips the context-put and no NPE fires.
3. **Lifecycle dead branch** — the `if (estimate > 0)` check inside `buildReservationEstimate` was provably unreachable given the outer guards (`tokens > 0` AND at least one rate > 0 means `tokens × rates > 0`). Removed the check; the comment explains why.
4. **Observation convention null-guards** — added two tests: convention called with `null` `ChatClientObservationContext`, and convention called with a request whose `context()` returns `null`. Both paths now exercised.
5. **Auto-config jtokkit-absent fallback** — extracted resolution to a public static helper `CyclesSpringAiAutoConfiguration.resolvePromptTokenEstimator(props, jtokkitOnClasspath)` (marked **internal API** in javadoc). Tests call it directly with `jtokkitOnClasspath=false` to exercise the fallback path — no classloader manipulation needed.

## Test strategy notes

- All unit tests use Mockito for `CyclesClient`. There is no live Cycles server in the unit-test suite — that's `cycles-server` integration territory.
- Stream tests use Reactor's `StepVerifier` to drive the Flux lifecycle (complete / error / cancel) without a real provider.
- The auto-configuration tests stub `CyclesClient` to satisfy `@ConditionalOnBean(CyclesClient.class)`.
- The integration test uses a stub `ChatModel` (anonymous `ChatModel` impl returning canned `ChatResponse`) to exercise the auto-attached advisor end-to-end without a real LLM provider.
- A Testcontainers-ollama smoke test is appropriate for a nightly CI job once a real-provider lane is needed, but is overkill for PR CI.
