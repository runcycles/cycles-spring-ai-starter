# Test Coverage Gaps

This file tracks known coverage shortfalls against the project's **95%-or-higher coverage rule** (see [CLAUDE.md](./CLAUDE.md)). Entries are removed as gaps are closed.

## Current state

- Library bundle (`cycles-spring-ai-starter`): **100% instruction coverage** (jacoco `check` passes; rule is BUNDLE INSTRUCTION ≥ 95%).
- Demo module (`cycles-spring-ai-demo`): not subject to the rule (not published).

The scaffold ships covered, including:
- `CyclesSpringAiProperties` — all getters/setters, all four properties.
- `CyclesSpringAiAutoConfiguration` — wiring contract (enabled / disabled / defaults / all-properties-set).
- `CyclesBudgetAdvisor` — `getName()`, `getOrder()`, `adviseCall()` pass-through.

## Open gaps

None at scaffold stage.

## Test gaps that will open with v0.1.0

When `CyclesBudgetAdvisor` gains real budget-gating logic, the following branches will need coverage to keep the 95% bar:

- Pre-call: assertion that budget-id is read from properties and forwarded to the Cycles client.
- Pre-call: assertion that the call is denied when the Cycles server reports over-budget.
- Post-call: assertion that `Usage` from `ChatResponse` is recorded back to Cycles.
- Error path: `failOpen=true` logs and proceeds; `failOpen=false` re-throws.

When `CyclesToolCallbackDecorator` lands (v0.2):
- Assertion that wrapped tool's metadata (name, description, JSON schema) is preserved.
- Assertion that pre-tool authority check denies before the wrapped tool executes.

## Test strategy

1. Unit tests with `MockChatModel` (Spring AI provides this in its test support) for the advisor's pre/post hooks.
2. Slice tests with `ApplicationContextRunner` for the auto-configuration matrix (enabled/disabled × on-classpath/off-classpath × property-validity). The current four tests already do this.
3. Integration test against ollama in Testcontainers for end-to-end advisor behavior with a real chat model (gated to nightly CI to keep PR CI fast). Lands with v0.1.0.
