# Test Coverage Gaps

This file tracks known coverage shortfalls against the project's **95%-or-higher coverage rule** (see [CLAUDE.md](./CLAUDE.md)). Entries are removed as gaps are closed.

## Current state (0.2.0-SNAPSHOT)

- Library bundle (`cycles-spring-ai-starter`): **100% instruction coverage, 100% branch coverage** (jacoco `check` passes; rule is BUNDLE INSTRUCTION ≥ 95%).
- Demo module (`cycles-spring-ai-demo`): not subject to the rule (not published).
- Test count: ~90 tests across 6 test classes (call advisor, stream advisor, tool callback, tool gate, auto-config, observation convention).

Covered surfaces:

- `CyclesSpringAiProperties` — all getters/setters across 11 properties, plus property-validation paths for every numeric setter that rejects negatives (`default-estimate`, `input-cost-per-token`, `output-cost-per-token`).
- `CyclesSpringAiAutoConfiguration` — full wiring matrix: enabled / disabled / missing `CyclesClient` bean / property defaults / all-properties-set / user-provided advisor backs off the auto-configured one / user-provided stream advisor backs off / user-provided named customizer backs off / observation-convention bean wired.
- `CyclesBudgetAdvisor` — full reserve → call → commit happy path; deny → throw without call; reserve transport / HTTP failures × fail-open / fail-closed; malformed 2xx reservation responses (unknown decision, missing reservation_id, whitespace reservation_id) treated as HTTP failures; commit transport / HTTP failures × fail-open / fail-closed; commit-failure-does-not-release-reservation; chain exception → release; release HTTP failure logged but does not mask original; fail-open reserve skip → no release on chain exception; unrecognized estimate unit fallback; **prompt-based estimate** (enabled + rates, only output rate, fallback when rates 0, fallback when prompt null, fallback when prompt empty, fallback when computed estimate is 0, multimodal null-text message handling); **real `ChatResponse.Usage` extraction** (cost-rate path, TOKENS-unit path, null-metadata fallback, null-usage fallback, null-totalTokens fallback in TOKENS unit, only-output-rate path); **all-null-token-breakdown falls back to estimate, single-null preserves what we have, literal-zero breakdowns commit zero** (distinct from missing).
- `CyclesBudgetStreamAdvisor` — `Flux.defer`-wrapped lifecycle: per-subscription reservation (no leak when never subscribed; distinct reservations across re-subscriptions); reserve denial / reserve transport failure both surface as `onError` (reactive-idiomatic); chain assembly failure releases + propagates; complete → commit using last-chunk usage; error → release; cancel → release; empty stream → commit with estimate; partial emission then error → release; commit failure surfaces as `onError` in fail-closed and is swallowed in fail-open; fail-open reserve skip; prompt-based estimate carries through the shared lifecycle.
- `CyclesToolCallback` — delegation (`getToolDefinition` / `getToolMetadata` pass-through); reserve → call → commit happy path with and without `ToolContext`; reservation carries tool-specific action labels (`tool.call`, `spring-ai-tool:<name>`); estimate-from-prompt skipped on the tool path (no request context); deny → throw, no tool invocation; tool runtime exception → release + re-throw; fail-open reserve skip; fail-open reserve skip with subsequent tool exception (no spurious release).
- `CyclesToolGate` — factory returns a `CyclesToolCallback` around the supplied delegate.
- `CyclesChatClientObservationConvention` — emits five `cycles.*` low-cardinality tags; inherits default convention keys via `super`; null SDK properties substituted as `unknown`.
- `CyclesBudgetDeniedException` — reasonCode / scopePath accessors covered transitively via deny paths.

## Open gaps

None at 0.2.0-SNAPSHOT. The bundle is at 100% instruction and 100% branch.

## Test strategy notes

- All unit tests use Mockito for `CyclesClient`. There is no live Cycles server in the unit-test suite — that's `cycles-server` integration territory, not Spring AI starter territory.
- Stream tests use Reactor's `StepVerifier` to drive the Flux lifecycle (complete / error / cancel) without a real provider.
- The auto-configuration tests stub `CyclesClient` to satisfy `@ConditionalOnBean(CyclesClient.class)`.
- For a future release, plan to add an integration test using Spring AI's `MockChatModel` so we can verify advisor attachment + behavior end-to-end without needing a real LLM provider.
- A Testcontainers-ollama smoke test is appropriate for a nightly CI job once a real-provider lane is needed, but is overkill for PR CI.
