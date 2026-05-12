# Test Coverage Gaps

This file tracks known coverage shortfalls against the project's **95%-or-higher coverage rule** (see [CLAUDE.md](./CLAUDE.md)). Entries are removed as gaps are closed.

## Current state

The scaffold contains skeleton classes only. Tests will land alongside the first real implementation; until then, jacoco's `check` rule (95% BUNDLE INSTRUCTION coverage) is configured in the pom.xml but unenforced because there is no business logic to cover.

## Open gaps

### Skeleton — applies to all initial classes
- `CyclesBudgetAdvisor`: no logic yet — empty `aroundCall` pass-through. Will need:
  - Pre-call: assertion that budget-id is read from properties and forwarded to the Cycles client.
  - Post-call: assertion that `Usage` from `ChatResponse` is recorded back to Cycles.
  - Error path: assertion that fail-open behavior gates correctly.
- `CyclesSpringAiAutoConfiguration`:
  - Assertion: bean wires only when Spring AI is on the classpath.
  - Assertion: bean does not wire when `cycles.spring-ai.enabled=false`.
  - Assertion: bean does not wire when `cycles.spring-ai.budget-id` is missing (and `enabled=true`) — should log a clear error rather than NPE later.

## Test strategy (planned for v0.1.0)

1. Unit tests with `MockChatModel` (Spring AI provides this in `spring-ai-test`) for the advisor's pre/post hooks.
2. Slice tests with `@SpringBootTest(classes = CyclesSpringAiAutoConfiguration.class)` for the auto-configuration matrix (enabled/disabled × on-classpath/off-classpath × property-validity).
3. Integration test against ollama in Testcontainers for end-to-end advisor behavior with a real chat model (gated to nightly CI to keep PR CI fast).
