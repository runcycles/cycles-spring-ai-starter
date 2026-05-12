# AUDIT.md — cycles-spring-ai-starter

This file tracks **protocol-surface** and **public-API** changes. Per the project rule (see [CLAUDE.md](./CLAUDE.md)): update this file whenever the public Java API, configuration property surface, or auto-configuration behavior changes.

## What "protocol surface" means here

The Spring AI starter does not own its own protocol — it delegates to the Cycles runtime via the existing client surface in [cycles-spring-boot-starter](https://github.com/runcycles/cycles-spring-boot-starter) (and through it, the [cycles-protocol](https://github.com/runcycles/cycles-protocol) YAML spec). Changes recorded here are limited to:

- Public Java types under `io.runcycles.client.java.springai.*`
- `@ConfigurationProperties` keys (`cycles.spring-ai.*`)
- Auto-configuration ordering / conditions
- Advisor `@Order` values and the contract they imply

## Initial public surface (v0.1.0)

| Component | FQCN | Stability |
|---|---|---|
| Auto-configuration | `io.runcycles.client.java.springai.autoconfigure.CyclesSpringAiAutoConfiguration` | API stable |
| Configuration properties | `io.runcycles.client.java.springai.autoconfigure.CyclesSpringAiProperties` (prefix: `cycles.spring-ai`) | API stable |
| Budget advisor | `io.runcycles.client.java.springai.advisor.CyclesBudgetAdvisor` | API stable; behavior may extend (e.g. per-call estimates, token-usage commit) without breaking existing callers |
| Denial exception | `io.runcycles.client.java.springai.CyclesBudgetDeniedException` | API stable |

## Property keys (v0.1.0)

| Key | Type | Default | Notes |
|---|---|---|---|
| `cycles.spring-ai.enabled` | boolean | `true` | Master switch; when `false`, no auto-configured beans register. |
| `cycles.spring-ai.default-estimate` | long | `1000` | Per-call reservation estimate value (until v0.2 derives per-call). |
| `cycles.spring-ai.estimate-unit` | string | `USD_MICROCENTS` | Cycles `Unit` enum name. Falls back to `USD_MICROCENTS` if unrecognized. |
| `cycles.spring-ai.action-kind` | string | `llm.chat` | Action.kind label reported to Cycles. |
| `cycles.spring-ai.action-name` | string | `spring-ai-chat` | Action.name label reported to Cycles. |
| `cycles.spring-ai.fail-open` | boolean | `false` | When `true`, transport / unexpected errors are logged and the call proceeds. Budget denials (`DENY` decision) are always surfaced regardless. |
| `cycles.spring-ai.input-cost-per-token` | long | `0` | **Added in 0.2.0-SNAPSHOT.** Per-input-token cost in the configured estimate unit. When > 0 (or `output-cost-per-token` > 0), enables real-token-based actual accounting at commit time. Rejected as IllegalArgumentException at binding when negative. |
| `cycles.spring-ai.output-cost-per-token` | long | `0` | **Added in 0.2.0-SNAPSHOT.** Per-output-token cost in the configured estimate unit. Same validation as the input rate. |

## Auto-configuration conditions

`CyclesSpringAiAutoConfiguration` activates when ALL of:

1. `org.springframework.ai.chat.client.ChatClient` is on the classpath.
2. `org.springframework.ai.chat.client.ChatClientCustomizer` is on the classpath.
3. A `CyclesClient` bean is registered (provided by `cycles-client-java-spring`'s `CyclesAutoConfiguration`).
4. `cycles.spring-ai.enabled=true` (default).

If any condition fails, the auto-configuration is skipped entirely (no `CyclesBudgetAdvisor` or `ChatClientCustomizer` beans).

## Advisor order

`CyclesBudgetAdvisor.getOrder()` returns `Ordered.HIGHEST_PRECEDENCE + 100`. Documented intent: run early enough that budget denial precedes any cost-incurring downstream advisor (e.g. RAG augmentation that incurs vector-store queries; chat-memory advisors that issue extra LLM calls).

If you stack additional Cycles advisors in v0.2 (`CyclesBudgetStreamAdvisor`, `CyclesAuthorityAdvisor`, etc.), they should follow the same precedence range to keep "deny before cost" semantics consistent.

## Wire-call contract per invocation

For each `chatClient.prompt(...).call()`:

1. `POST /v1/reservations` — body includes subject (tenant/workspace/app/workflow/agent/toolset from `CyclesProperties`), action (kind/name from `CyclesSpringAiProperties`), estimate (default-estimate × estimate-unit), idempotency_key (per-call UUID).
2. If decision is `DENY` → `CyclesBudgetDeniedException` thrown, no LLM call.
3. If decision is `ALLOW`/`ALLOW_WITH_CAPS` → `chain.nextCall(request)` proceeds.
4. On success → `POST /v1/reservations/{id}/commit` with actual computed from response usage:
   - `estimate-unit=TOKENS` + usage present → actual = `usage.getTotalTokens()`.
   - `input-cost-per-token` and/or `output-cost-per-token` set + usage present → actual = `(promptTokens × inputRate) + (completionTokens × outputRate)`.
   - Otherwise → actual = estimate (v0.1.0-compatible fallback).
5. On exception → `POST /v1/reservations/{id}/release` with reason = `chat-call-failed: <ExceptionClass>`, then the original exception is re-thrown.

## Change log

### v0.1.0 (TBD)
Initial public surface. See [CHANGELOG.md](./CHANGELOG.md).
