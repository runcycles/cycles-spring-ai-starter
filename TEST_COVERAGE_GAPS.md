# Test Coverage Gaps

This file tracks known coverage shortfalls against the project's **95%-or-higher coverage rule** (see [CLAUDE.md](./CLAUDE.md)). Entries are removed as gaps are closed.

## Current state (v0.1.0)

- Library bundle (`cycles-spring-ai-starter`): **100% instruction coverage** (jacoco `check` passes; rule is BUNDLE INSTRUCTION ≥ 95%).
- Demo module (`cycles-spring-ai-demo`): not subject to the rule (not published).

Covered:
- `CyclesSpringAiProperties` — all getters/setters across 6 properties, plus property-validation paths (negative `default-estimate` rejected at binding time; zero accepted).
- `CyclesSpringAiAutoConfiguration` — full wiring matrix: enabled / disabled / missing `CyclesClient` bean / property defaults / all-properties-set / **user-provided advisor backs off the auto-configured one** / **user-provided customizer (same name) backs off the auto-configured one**.
- `CyclesBudgetAdvisor` — full reserve → call → commit happy path; deny → throw without call; reserve transport / HTTP failures across fail-open and fail-closed; malformed 2xx reservation responses (unknown decision, missing reservation_id) treated as HTTP failures; commit transport / HTTP failures across fail-open and fail-closed; **commit-failure-does-not-release-reservation** (post-call commit threw → reservation NOT released because LLM call succeeded and budget was consumed); chain exception → release; release HTTP failure logged but does not mask original; fail-open reserve skip → no release on chain exception; unrecognized estimate unit fallback.
- `CyclesBudgetDeniedException` — reasonCode / scopePath accessors covered transitively via deny path.

## Open gaps

None at v0.1.0.

## Test gaps that will open in v0.2

When new functionality lands, the following branches will need coverage to keep the 95% bar:

- **Streaming (`StreamAdvisor`)**: pre/post hooks, error paths, fail-open variants — mirror the call-advisor test matrix.
- **Per-call estimate derivation**: when the estimate is computed from prompt token count, test the edge cases (empty prompt, oversized prompt, missing tokenizer for model).
- **Token-usage commit**: when commit reads real `ChatResponse.Usage`, cover the provider-specific paths (OpenAI usage shape, Anthropic shape, missing/null usage).
- **`ToolCallback` decoration**: pre-tool authority check denies, decorated tool preserves metadata (name/description/JSON schema), wrapped tool's return value is forwarded unchanged.
- **`ObservationConvention`**: high-cardinality vs low-cardinality keys, subject/tenant attribution propagation through observation context.

## Test strategy notes

- All unit tests use Mockito for `CyclesClient`. There is no live Cycles server in the unit-test suite — that's `cycles-server` integration territory, not Spring AI starter territory.
- The auto-configuration tests stub `CyclesClient` to satisfy `@ConditionalOnBean(CyclesClient.class)`.
- For v0.2, plan to add an integration test using Spring AI's `MockChatModel` so we can verify advisor attachment + behavior end-to-end without needing a real LLM provider.
- A Testcontainers-ollama smoke test is appropriate for a nightly CI job once v0.2 has real usage extraction, but is overkill for PR CI.
